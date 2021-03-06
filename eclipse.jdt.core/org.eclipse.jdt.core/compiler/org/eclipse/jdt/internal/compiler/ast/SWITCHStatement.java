/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.*;
import org.eclipse.jdt.internal.compiler.flow.*;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.*;

/**
 * 
 * @author milan
 *
 */
public class SWITCHStatement extends Statement {

	public Expression expression;
	public Statement[] statements;
	public BlockScope scope;
	public int explicitDeclarations;
	public BranchLabel breakLabel;
	public CASEStatement[] cases;
	public CASEStatement defaultCase;
	public int blockStart;
	public int caseCount;
	int[] constants;
	public ThisReference thisReference;

	// fallthrough
	public final static int CASE = 0;
	public final static int FALLTHROUGH = 1;
	public final static int ESCAPING = 2;

	public MethodBinding switchAppropriateMethodForOverload = null;
	public MethodBinding endswitchAppropriateMethodForOverload = null;
	private MethodBinding switchSyntheticAccessor = null;
	private MethodBinding endswitchSyntheticAccessor = null;
	public SyntheticMethodBinding synthetic; // use for switch on enums types

	// for local variables table attributes
	int preSwitchInitStateIndex = -1;
	int mergedInitStateIndex = -1;
	
	public SWITCHStatement(ThisReference thisReference){
		this.thisReference = thisReference;
	}

	public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
	    try {
			flowInfo = this.expression.analyseCode(currentScope, flowContext, flowInfo);
			
			if(this.switchAppropriateMethodForOverload != null){
				MethodBinding original = this.switchAppropriateMethodForOverload.original();
				if(original.isPrivate()){
					this.switchSyntheticAccessor = ((SourceTypeBinding)original.declaringClass).addSyntheticMethod(original, false /* not super access there */);
					currentScope.problemReporter().needToEmulateMethodAccess(original, this);
				}
			}
			if(this.endswitchAppropriateMethodForOverload != null){
				MethodBinding original = this.endswitchAppropriateMethodForOverload.original();
				if(original.isPrivate()){
					this.endswitchSyntheticAccessor = ((SourceTypeBinding)original.declaringClass).addSyntheticMethod(original, false /* not super access there */);
					currentScope.problemReporter().needToEmulateMethodAccess(original, this);
				}
			}
			
			SWITCHFlowContext switchContext =
				new SWITCHFlowContext(flowContext, this, (this.breakLabel = new BranchLabel()));

			// analyse the block by considering specially the case/default statements (need to bind them
			// to the entry point)
			FlowInfo caseInits = FlowInfo.DEAD_END;
			// in case of statements before the first case
			this.preSwitchInitStateIndex = currentScope.methodScope().recordInitializationStates(flowInfo);
			int caseIndex = 0;
			if (this.statements != null) {
				int initialComplaintLevel = (flowInfo.reachMode() & FlowInfo.UNREACHABLE) != 0 ? Statement.COMPLAINED_FAKE_REACHABLE : Statement.NOT_COMPLAINED;
				int complaintLevel = initialComplaintLevel;
				int fallThroughState = CASE;
				for (int i = 0, max = this.statements.length; i < max; i++) {
					Statement statement = this.statements[i];
					if ((caseIndex < this.caseCount) && (statement == this.cases[caseIndex])) { // statement is a case
						this.scope.enclosingCASE = this.cases[caseIndex]; // record entering in a switch case block
						caseIndex++;
						if (fallThroughState == FALLTHROUGH
								&& (statement.bits & ASTNode.DocumentedFallthrough) == 0) { // the case is not fall-through protected by a line comment
//							this.scope.problemReporter().possibleFallThroughCase(this.scope.enclosingCASE);
						}
						caseInits = caseInits.mergedWith(flowInfo.unconditionalInits());
						complaintLevel = initialComplaintLevel; // reset complaint
						fallThroughState = CASE;
					} else if (statement == this.defaultCase) { // statement is the default case
						this.scope.enclosingCASE = this.defaultCase; // record entering in a switch case block
						if (fallThroughState == FALLTHROUGH
								&& (statement.bits & ASTNode.DocumentedFallthrough) == 0) {
//							this.scope.problemReporter().possibleFallThroughCase(this.scope.enclosingCase);
						}
						caseInits = caseInits.mergedWith(flowInfo.unconditionalInits());
						complaintLevel = initialComplaintLevel; // reset complaint
						fallThroughState = CASE;
					} else {
						fallThroughState = FALLTHROUGH; // reset below if needed
					}
					if ((complaintLevel = statement.complainIfUnreachable(caseInits, this.scope, complaintLevel, false)) < Statement.COMPLAINED_UNREACHABLE) {
						caseInits = statement.analyseCode(this.scope, switchContext, caseInits);
						if (caseInits == FlowInfo.DEAD_END) {
							fallThroughState = ESCAPING;
						}
					}
				}
			}

			final TypeBinding resolvedTypeBinding = this.expression.resolvedType;
			if (this.caseCount > 0 && resolvedTypeBinding.isEnum()) {
				final SourceTypeBinding sourceTypeBinding = this.scope.classScope().referenceContext.binding;
				this.synthetic = sourceTypeBinding.addSyntheticMethodForSwitchEnum(resolvedTypeBinding);
			}
			// if no default case, then record it may jump over the block directly to the end
			if (this.defaultCase == null) {
				// only retain the potential initializations
				flowInfo.addPotentialInitializationsFrom(caseInits.mergedWith(switchContext.initsOnBreak));
				this.mergedInitStateIndex = currentScope.methodScope().recordInitializationStates(flowInfo);
				return flowInfo;
			}

			// merge all branches inits
			FlowInfo mergedInfo = caseInits.mergedWith(switchContext.initsOnBreak);
			this.mergedInitStateIndex =
				currentScope.methodScope().recordInitializationStates(mergedInfo);
			return mergedInfo;
	    } finally {
	        if (this.scope != null) this.scope.enclosingCase = null; // no longer inside switch case block
	    }
	}

	/**
	 * Switch code generation
	 *
	 * @param currentScope org.eclipse.jdt.internal.compiler.lookup.BlockScope
	 * @param codeStream org.eclipse.jdt.internal.compiler.codegen.CodeStream
	 */
	public void generateCode(BlockScope currentScope, CodeStream codeStream) {
		this.generateOperatorOverloadCodeForSWITCH(this.switchAppropriateMethodForOverload, currentScope, codeStream, true);

		try {
			if ((this.bits & IsReachable) == 0) {
				return;
			}
			int pc = codeStream.position;

			// prepare the labels and constants
			this.breakLabel.initialize(codeStream);
			CaseLabel[] caseLabels = new CaseLabel[this.caseCount];
			boolean needSwitch = this.caseCount != 0;
			for (int i = 0; i < this.caseCount; i++) {
				this.cases[i].targetLabel = (caseLabels[i] = new CaseLabel(codeStream));
				caseLabels[i].tagBits |= BranchLabel.USED;
			}
			CaseLabel defaultLabel = new CaseLabel(codeStream);
			if (needSwitch) defaultLabel.tagBits |= BranchLabel.USED;
			if (this.defaultCase != null) {
				this.defaultCase.targetLabel = defaultLabel;
			}

			// generate the switch block statements
			int caseIndex = 0;
			if (this.statements != null) {
				for (int i = 0, maxCases = this.statements.length; i < maxCases; i++) {
					Statement statement = this.statements[i];
					if ((caseIndex < this.caseCount) && (statement == this.cases[caseIndex])) { // statements[i] is a case
						this.scope.enclosingCASE = this.cases[caseIndex]; // record entering in a switch case block
						if (this.preSwitchInitStateIndex != -1) {
							codeStream.removeNotDefinitelyAssignedVariables(currentScope, this.preSwitchInitStateIndex);
						}
						caseIndex++;
					} else {
						if (statement == this.defaultCase) { // statements[i] is a case or a default case
							this.scope.enclosingCASE = this.defaultCase; // record entering in a switch case block
							if (this.preSwitchInitStateIndex != -1) {
								codeStream.removeNotDefinitelyAssignedVariables(currentScope, this.preSwitchInitStateIndex);
							}
						}
					}
					statement.generateCode(this.scope, codeStream);
				}
			}
			this.generateOperatorOverloadCodeForENDSWITCH(this.endswitchAppropriateMethodForOverload, currentScope, codeStream, true);
			// May loose some local variable initializations : affecting the local variable attributes
			if (this.mergedInitStateIndex != -1) {
				codeStream.removeNotDefinitelyAssignedVariables(currentScope, this.mergedInitStateIndex);
				codeStream.addDefinitelyAssignedVariables(currentScope, this.mergedInitStateIndex);
			}
			if (this.scope != currentScope) {
				codeStream.exitUserScope(this.scope);
			}
			// place the trailing labels (for break and default case)
			this.breakLabel.place();
			if (this.defaultCase == null) {
				// we want to force an line number entry to get an end position after the switch statement
				codeStream.recordPositionsFrom(codeStream.position, this.sourceEnd, true);
				defaultLabel.place();
			}
			codeStream.recordPositionsFrom(pc, this.sourceStart);
		} finally {
			if (this.scope != null) this.scope.enclosingCase = null; // no longer inside switch case block
		}
	}

	public StringBuffer printStatement(int indent, StringBuffer output) {

		printIndent(indent, output).append("SWITCH ("); //$NON-NLS-1$
		this.expression.printExpression(0, output).append(") {"); //$NON-NLS-1$
		if (this.statements != null) {
			for (int i = 0; i < this.statements.length; i++) {
				output.append('\n');
				if (this.statements[i] instanceof CASEStatement) {
					this.statements[i].printStatement(indent, output);
				} else {
					this.statements[i].printStatement(indent+2, output);
				}
			}
		}
		output.append("\n"); //$NON-NLS-1$
		return printIndent(indent, output).append('}');
	}

	public void resolve(BlockScope upperScope) {

	    try {
	    	
	    	TypeBinding expressionType = this.expression.resolvedType;
	    	if(expressionType == null){
	    		expressionType = this.expression.resolveType(upperScope);
	    		if(expressionType == null)
	    			return;
	    	}
	    	
			MethodBinding mb2 = this.getMethodBindingForOverloadForSWITCH(upperScope);
			if (mb2 != null && mb2.isValidBinding()) {
				if((mb2.modifiers & ClassFileConstants.AccStatic) != 0) {
					upperScope.problemReporter().overloadedOperatorMethodNotStatic(this, getMethodNameForSWITCH());
					return;
				}
				if(mb2.returnType != TypeBinding.VOID){
					upperScope.problemReporter().invalidReturnTypeForOverloadedOperator(this, getMethodNameForSWITCH(), TypeBinding.VOID, mb2.returnType);
					return;
				}
				this.switchAppropriateMethodForOverload = mb2;
				if (isMethodUseDeprecated(this.switchAppropriateMethodForOverload, scope, true))
					scope.problemReporter().deprecatedMethod(this.switchAppropriateMethodForOverload, this);
				if(this.thisReference.resolvedType == null)
					this.thisReference.resolveType(upperScope);
				this.thisReference.computeConversion(upperScope, this.thisReference.resolvedType, this.thisReference.resolvedType);
				this.expression.computeConversion(upperScope, this.switchAppropriateMethodForOverload.parameters[0], this.expression.resolvedType);

				
			}else{
				if(this.expression == null)
					upperScope.problemReporter().invalidOrMissingOverloadedOperator(this, getMethodNameForSWITCH(), TypeBinding.VOID);
				else
					upperScope.problemReporter().invalidOrMissingOverloadedOperator(this, getMethodNameForSWITCH(), this.expression.resolvedType);
				return;
			}

			mb2 = this.getMethodBindingForOverloadForENDSWITCH(upperScope);
			if (mb2.isValidBinding()) {
				if((mb2.modifiers & ClassFileConstants.AccStatic) != 0) {
					upperScope.problemReporter().overloadedOperatorMethodNotStatic(this, getMethodNameForENDSWITCH());
					return;
				}
				if(mb2.returnType != TypeBinding.VOID){
					upperScope.problemReporter().invalidReturnTypeForOverloadedOperator(this, getMethodNameForENDSWITCH(), TypeBinding.VOID, mb2.returnType);
					return;
				}
				this.endswitchAppropriateMethodForOverload = mb2;
				if (isMethodUseDeprecated(this.endswitchAppropriateMethodForOverload, scope, true))
					scope.problemReporter().deprecatedMethod(this.endswitchAppropriateMethodForOverload, this);
			}else{
				upperScope.problemReporter().invalidOrMissingOverloadedOperator(this, getMethodNameForENDSWITCH(), TypeBinding.VOID);
					return;
			}

			

			if (this.statements != null) {
				this.scope = /*explicitDeclarations == 0 ? upperScope : */new BlockScope(upperScope);
				int length;
				// collection of cases is too big but we will only iterate until caseCount
				this.cases = new CASEStatement[length = this.statements.length];
				this.constants = new int[length];
				for (int i = 0; i < length; i++) {
					final Statement statement = this.statements[i];
					if (statement.resolveCase(this.scope, expressionType, this) == Constant.NotAConstant) {
						/**
						 * Additional CASE invoke code ...
						 */
					}
				}
			} else {
				if ((this.bits & UndocumentedEmptyBlock) != 0) {
					upperScope.problemReporter().undocumentedEmptyBlock(this.blockStart, this.sourceEnd);
				}
				this.scope = new BlockScope(upperScope);
			}
	    } finally {
	        if (this.scope != null) this.scope.enclosingCase = null; // no longer inside switch case block
	    }
	}

	public void traverse(
			ASTVisitor visitor,
			BlockScope blockScope) {

		if (visitor.visit(this, blockScope)) {
			this.expression.traverse(visitor, this.scope);
			if (this.statements != null) {
				int statementsLength = this.statements.length;
				for (int i = 0; i < statementsLength; i++)
					this.statements[i].traverse(visitor, this.scope);
			}
		}
		visitor.endVisit(this, blockScope);
	}

	/**
	 * Dispatch the call on its last statement.
	 */
	public void branchChainTo(BranchLabel label) {

		// in order to improve debug attributes for stepping (11431)
		// we want to inline the jumps to #breakLabel which already got
		// generated (if any), and have them directly branch to a better
		// location (the argument label).
		// we know at this point that the breakLabel already got placed
		if (this.breakLabel.forwardReferenceCount() > 0) {
			label.becomeDelegateFor(this.breakLabel);
		}
	}
	/**
	 * Milan :new SWITCH
	 */
	private String getMethodNameForSWITCH() {
		return "_SWITCH"; //$NON-NLS-1$
	}

	private String getMethodNameForENDSWITCH() {
		return "_END_SWITCH"; //$NON-NLS-1$
	}

	public MethodBinding getMethodBindingForOverloadForSWITCH(BlockScope localScope) {
		TypeBinding tb = null; 
		TypeBinding [] tb_right = new TypeBinding[] {this.expression.resolvedType};
		tb = localScope.parent.classScope().referenceContext.binding;
		InvocationSite fakeInvocationSite = new InvocationSite(){
			public TypeBinding[] genericTypeArguments() { return null; }
			public boolean isSuperAccess(){ return false; }
			public boolean isTypeAccess() { return true; }
			public void setActualReceiverType(ReferenceBinding actualReceiverType) { /* ignore */}
			public void setDepth(int depth) { /* ignore */}
			public void setFieldIndex(int depth){ /* ignore */}
			public int sourceStart() { return 0; }
			public int sourceEnd() { return 0; }
			public TypeBinding expectedType() {
				return null;
			}
			@Override
			public TypeBinding invocationTargetType() {
				// TODO Auto-generated method stub
				throw new RuntimeException("Implement this");
//				return null;
			}
			@Override
			public boolean receiverIsImplicitThis() {
				// TODO Auto-generated method stub
				throw new RuntimeException("Implement this");
//				return false;
			}
			@Override
			public InferenceContext18 freshInferenceContext(Scope scope) {
				// TODO Auto-generated method stub
				throw new RuntimeException("Implement this");
//				return null;
			}
			@Override
			public ExpressionContext getExpressionContext() {
				// TODO Auto-generated method stub
				throw new RuntimeException("Implement this");
//				return null;
			}
		};

		String ms = getMethodNameForSWITCH();
		
		MethodBinding mb2 = localScope.parent.getMethod(tb, ms.toCharArray(), tb_right,  fakeInvocationSite);
		return mb2;
	}

	public void generateOperatorOverloadCodeForSWITCH(MethodBinding mb2, BlockScope currentScope, CodeStream codeStream, boolean valueRequired) {
		this.thisReference.generateCode(currentScope, codeStream, true);
		this.expression.generateCode(currentScope, codeStream, true);
		if (mb2.hasSubstitutedParameters() || mb2.hasSubstitutedReturnType()) {			
			TypeBinding tbo = mb2.returnType;
			MethodBinding mb3 = mb2.original(); 
			MethodBinding final_mb = mb3;
			codeStream.checkcast(final_mb.declaringClass);
			codeStream.invoke((final_mb.declaringClass.isInterface()) ? Opcodes.OPC_invokeinterface : Opcodes.OPC_invokevirtual, final_mb, final_mb.declaringClass.erasure());
			if (tbo.erasure().isProvablyDistinct(final_mb.returnType.erasure())) {
				codeStream.checkcast(tbo);
			}
		} else {
			MethodBinding original = mb2.original();
			if(original.isPrivate()){
				codeStream.invoke(Opcodes.OPC_invokestatic, this.switchSyntheticAccessor, null /* default declaringClass */);
			}
			else{
				codeStream.invoke((original.declaringClass.isInterface()) ? Opcodes.OPC_invokeinterface : Opcodes.OPC_invokevirtual, original, original.declaringClass);
			}
			if (!mb2.returnType.isBaseType()) codeStream.checkcast(mb2.returnType);

		}
	}
	/**
	 * endswitch
	 */
	public MethodBinding getMethodBindingForOverloadForENDSWITCH(BlockScope localScope) {
		TypeBinding tb = null; 
		TypeBinding [] tb_right = new TypeBinding[] {};
		tb = localScope.parent.classScope().referenceContext.binding;
		InvocationSite fakeInvocationSite = new InvocationSite(){
			public TypeBinding[] genericTypeArguments() { return null; }
			public boolean isSuperAccess(){ return false; }
			public boolean isTypeAccess() { return true; }
			public void setActualReceiverType(ReferenceBinding actualReceiverType) { /* ignore */}
			public void setDepth(int depth) { /* ignore */}
			public void setFieldIndex(int depth){ /* ignore */}
			public int sourceStart() { return 0; }
			public int sourceEnd() { return 0; }
			public TypeBinding expectedType() {
				return null;
			}
			@Override
			public TypeBinding invocationTargetType() {
				// TODO Auto-generated method stub
				throw new RuntimeException("Implement this");
//				return null;
			}
			@Override
			public boolean receiverIsImplicitThis() {
				// TODO Auto-generated method stub
				throw new RuntimeException("Implement this");
//				return false;
			}
			@Override
			public InferenceContext18 freshInferenceContext(Scope scope) {
				// TODO Auto-generated method stub
				throw new RuntimeException("Implement this");
//				return null;
			}
			@Override
			public ExpressionContext getExpressionContext() {
				// TODO Auto-generated method stub
				throw new RuntimeException("Implement this");
//				return null;
			}
		};

		String ms = getMethodNameForENDSWITCH();
		
		MethodBinding mb2 = localScope.parent.getMethod(tb, ms.toCharArray(), tb_right,  fakeInvocationSite);
		return mb2;
	}

	public void generateOperatorOverloadCodeForENDSWITCH(MethodBinding mb2, BlockScope currentScope, CodeStream codeStream, boolean valueRequired) {
		this.thisReference.generateCode(currentScope, codeStream, true);
		if (mb2.hasSubstitutedParameters() || mb2.hasSubstitutedReturnType()) {			
			TypeBinding tbo = mb2.returnType;
			MethodBinding mb3 = mb2.original(); 
			MethodBinding final_mb = mb3;
			codeStream.checkcast(final_mb.declaringClass);
			codeStream.invoke((final_mb.declaringClass.isInterface()) ? Opcodes.OPC_invokeinterface : Opcodes.OPC_invokevirtual, final_mb, final_mb.declaringClass.erasure());
			if (tbo.erasure().isProvablyDistinct(final_mb.returnType.erasure())) {
				codeStream.checkcast(tbo);
			}
		} else {
			MethodBinding original = mb2.original();
			if(original.isPrivate()){
				codeStream.invoke(Opcodes.OPC_invokestatic, this.endswitchSyntheticAccessor, null /* default declaringClass */);
			}
			else{
				codeStream.invoke((mb2.declaringClass.isInterface()) ? Opcodes.OPC_invokeinterface : Opcodes.OPC_invokevirtual, mb2, mb2.declaringClass);
			}
		}
	}
}

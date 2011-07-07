/*
 [The "BSD license"]
 Copyright (c) 2011 Terence Parr
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
    derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.antlr.v4.codegen;

import org.antlr.runtime.tree.*;
import org.antlr.v4.codegen.model.*;
import org.antlr.v4.codegen.model.ast.*;
import org.antlr.v4.codegen.model.decl.CodeBlock;
import org.antlr.v4.parse.*;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.*;

import java.util.*;

/** This receives events from SourceGenTriggers.g and asks factory to do work.
 *  Then runs extensions in order on resulting SrcOps to get final list.
 **/
public class OutputModelController {
	/** Who does the work? Doesn't have to be CoreOutputModelFactory. */
	public OutputModelFactory delegate;

	/** Post-processing CodeGeneratorExtension objects; done in order given. */
	public List<CodeGeneratorExtension> extensions = new ArrayList<CodeGeneratorExtension>();

	/** While walking code in rules, this is set to the tree walker that
	 *  triggers actions.
	 */
	public SourceGenTriggers walker;

	public OutputModelController(OutputModelFactory factory) {
		this.delegate = factory;
	}

	public void addExtension(CodeGeneratorExtension ext) { extensions.add(ext); }

	/** Build a file with a parser containing rule functions. Use the
	 *  controller as factory in SourceGenTriggers so it triggers codegen
	 *  extensions too, not just the factory functions in this factory.
	 */
	public OutputModelObject buildParserOutputModel() {
		Grammar g = delegate.getGrammar();
		CodeGenerator gen = delegate.getGenerator();
		ParserFile file = parserFile(gen.getRecognizerFileName());
		setRoot(file);
		Parser parser = parser(file);
		file.parser = parser;

		for (Rule r : g.rules.values()) {
			RuleFunction function = rule(r);
			parser.funcs.add(function);

			// TRIGGER factory functions for rule alts, elements
			pushCurrentRule(function);
			GrammarASTAdaptor adaptor = new GrammarASTAdaptor(r.ast.token.getInputStream());
			GrammarAST blk = (GrammarAST)r.ast.getFirstChildWithType(ANTLRParser.BLOCK);
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor,blk);
			walker = new SourceGenTriggers(nodes, this);
			try {
				function.code = DefaultOutputModelFactory.list(walker.block(null, null)); // walk AST of rule alts/elements
			}
			catch (Exception e){
				e.printStackTrace(System.err);
			}

			function.ctxType = gen.target.getRuleFunctionContextStructName(r);
			function.ruleCtx.name = function.ctxType;

			function.postamble = rulePostamble(function, r);

			if ( function.ruleCtx.isEmpty() ) function.ruleCtx = null;
			popCurrentRule();
		}

		return file;
	}

	public OutputModelObject buildLexerOutputModel() {
		CodeGenerator gen = delegate.getGenerator();
		LexerFile file = lexerFile(gen.getRecognizerFileName());
		setRoot(file);
		file.lexer = lexer(file);
		return file;
	}

	public ParserFile parserFile(String fileName) {
		ParserFile f = delegate.parserFile(fileName);
		for (CodeGeneratorExtension ext : extensions) f = ext.parserFile(f);
		return f;
	}

	public Parser parser(ParserFile file) {
		Parser p = delegate.parser(file);
		for (CodeGeneratorExtension ext : extensions) p = ext.parser(p);
		return p;
	}

	public LexerFile lexerFile(String fileName) {
		return new LexerFile(delegate, getGenerator().getRecognizerFileName());
	}

	public Lexer lexer(LexerFile file) {
		return new Lexer(delegate, file);
	}

	public RuleFunction rule(Rule r) {
		RuleFunction rf = delegate.rule(r);
		for (CodeGeneratorExtension ext : extensions) rf = ext.rule(rf);
		return rf;
	}

	public List<SrcOp> rulePostamble(RuleFunction function, Rule r) {
		List<SrcOp> ops = delegate.rulePostamble(function, r);
		for (CodeGeneratorExtension ext : extensions) ops = ext.rulePostamble(ops);
		return ops;
	}

	public Grammar getGrammar() { return delegate.getGrammar(); }

	public CodeGenerator getGenerator() { return delegate.getGenerator(); }

	public CodeBlockForAlt alternative(Alternative alt) {
		CodeBlockForAlt blk = delegate.alternative(alt);
		for (CodeGeneratorExtension ext : extensions) blk = ext.alternative(blk);
		return blk;
	}

	public CodeBlockForAlt finishAlternative(CodeBlockForAlt blk, List<SrcOp> ops) {
		blk = delegate.finishAlternative(blk, ops);
		for (CodeGeneratorExtension ext : extensions) blk = ext.finishAlternative(blk);
		return blk;
	}

	public List<SrcOp> ruleRef(GrammarAST ID, GrammarAST label, GrammarAST args) {
		List<SrcOp> ops = delegate.ruleRef(ID, label, args);
		for (CodeGeneratorExtension ext : extensions) {
			ops = ext.ruleRef(ops);
			Tree parent = ID.getParent();
			if ( parent!=null && parent.getType()!=ANTLRParser.BANG &&
				 parent.getType()!=ANTLRParser.ROOT )
			{
				ops = ext.leafRule(ops);
			}
		}
		return ops;
	}

	public List<SrcOp> tokenRef(GrammarAST ID, GrammarAST label, GrammarAST args) {
		List<SrcOp> ops = delegate.tokenRef(ID, label, args);
		for (CodeGeneratorExtension ext : extensions) {
			ops = ext.tokenRef(ops);
			Tree parent = ID.getParent();
			if ( parent!=null && parent.getType()!=ANTLRParser.BANG &&
				 parent.getType()!=ANTLRParser.ROOT )
			{
				ops = ext.leafToken(ops);
			}
		}
		return ops;
	}

	public List<SrcOp> stringRef(GrammarAST ID, GrammarAST label) {
		List<SrcOp> ops = delegate.stringRef(ID, label);
		for (CodeGeneratorExtension ext : extensions) ops = ext.stringRef(ops);
		return ops;
	}

	public CodeBlockForAlt epsilon() {
		CodeBlockForAlt blk = delegate.epsilon();
		for (CodeGeneratorExtension ext : extensions) blk = ext.epsilon(blk);
		return blk;
	}

	public List<SrcOp> action(GrammarAST ast) {
		List<SrcOp> ops = delegate.action(ast);
		for (CodeGeneratorExtension ext : extensions) ops = ext.action(ops);
		return ops;
	}

	public List<SrcOp> forcedAction(GrammarAST ast) {
		List<SrcOp> ops = delegate.forcedAction(ast);
		for (CodeGeneratorExtension ext : extensions) ops = ext.forcedAction(ops);
		return ops;
	}

	public List<SrcOp> sempred(GrammarAST ast) {
		List<SrcOp> ops = delegate.sempred(ast);
		for (CodeGeneratorExtension ext : extensions) ops = ext.sempred(ops);
		return ops;
	}

	public List<SrcOp> rootToken(List<SrcOp> ops) {
		ops = delegate.rootToken(ops);
		for (CodeGeneratorExtension ext : extensions) ops = ext.rootToken(ops);
		return ops;
	}

	public List<SrcOp> rootRule(List<SrcOp> ops) {
		ops = delegate.rootRule(ops);
		for (CodeGeneratorExtension ext : extensions) ops = ext.rootRule(ops);
		return ops;
	}

	public Choice getChoiceBlock(BlockAST blkAST, List<CodeBlockForAlt> alts) {
		Choice c = delegate.getChoiceBlock(blkAST, alts);
		List<SrcOp> ops = DefaultOutputModelFactory.list(c);
		for (CodeGeneratorExtension ext : extensions) ops = ext.getChoiceBlock(ops);
		return c;
	}

	public Choice getEBNFBlock(GrammarAST ebnfRoot, List<CodeBlockForAlt> alts) {
		Choice c = delegate.getEBNFBlock(ebnfRoot, alts);
		List<SrcOp> ops = DefaultOutputModelFactory.list(c);
		for (CodeGeneratorExtension ext : extensions) ops = ext.getEBNFBlock(ops);
		return c;
	}

	public Choice getLL1ChoiceBlock(BlockAST blkAST, List<CodeBlockForAlt> alts) {
		Choice c = delegate.getLL1ChoiceBlock(blkAST, alts);
		List<SrcOp> ops = DefaultOutputModelFactory.list(c);
		for (CodeGeneratorExtension ext : extensions) ops = ext.getLL1ChoiceBlock(ops);
		return c;
	}

	public Choice getLLStarChoiceBlock(BlockAST blkAST, List<CodeBlockForAlt> alts) {
		Choice c = delegate.getLLStarChoiceBlock(blkAST, alts);
		List<SrcOp> ops = DefaultOutputModelFactory.list(c);
		for (CodeGeneratorExtension ext : extensions) ops = ext.getLLStarChoiceBlock(ops);
		return c;
	}

	public Choice getLL1EBNFBlock(GrammarAST ebnfRoot, List<CodeBlockForAlt> alts) {
		Choice c = delegate.getLL1EBNFBlock(ebnfRoot, alts);
		List<SrcOp> ops = DefaultOutputModelFactory.list(c);
		for (CodeGeneratorExtension ext : extensions) ops = ext.getLL1EBNFBlock(ops);
		return c;
	}

	public Choice getLLStarEBNFBlock(GrammarAST ebnfRoot, List<CodeBlockForAlt> alts) {
		Choice c = delegate.getLLStarEBNFBlock(ebnfRoot, alts);
		List<SrcOp> ops = DefaultOutputModelFactory.list(c);
		for (CodeGeneratorExtension ext : extensions) ops = ext.getLLStarEBNFBlock(ops);
		return c;
	}

	public List<SrcOp> getLL1Test(IntervalSet look, GrammarAST blkAST) {
		List<SrcOp> ops = delegate.getLL1Test(look, blkAST);
		for (CodeGeneratorExtension ext : extensions) ops = ext.getLL1Test(ops);
		return ops;
	}

	public boolean needsImplicitLabel(GrammarAST ID, LabeledOp op) {
		boolean needs = delegate.needsImplicitLabel(ID, op);
		for (CodeGeneratorExtension ext : extensions) needs |= ext.needsImplicitLabel(ID, op);
		return needs;
	}

	// REWRITES

	public TreeRewrite treeRewrite(GrammarAST ast) {
		TreeRewrite r = delegate.treeRewrite(ast);
		for (CodeGeneratorExtension ext : extensions) r = ext.treeRewrite(r);
		return r;
	}

	public RewriteTreeOptional rewrite_optional(GrammarAST ast) {
		RewriteTreeOptional o = delegate.rewrite_optional(ast);
		for (CodeGeneratorExtension ext : extensions) o = ext.rewrite_optional(o);
		return o;
	}

	public RewriteTreeClosure rewrite_closure(GrammarAST ast) {
		RewriteTreeClosure c = delegate.rewrite_closure(ast);
		for (CodeGeneratorExtension ext : extensions) c = ext.rewrite_closure(c);
		return c;
	}

	public RewriteTreeStructure rewrite_tree(GrammarAST root) {
		RewriteTreeStructure t = delegate.rewrite_tree(root);
		for (CodeGeneratorExtension ext : extensions) t = ext.rewrite_tree(t);
		return t;
	}

	public List<SrcOp> rewrite_ruleRef(GrammarAST ID, boolean isRoot) {
		List<SrcOp> ops = delegate.rewrite_ruleRef(ID, isRoot);
		for (CodeGeneratorExtension ext : extensions) ops = ext.rewrite_ruleRef(ops);
		return ops;
	}

	public List<SrcOp> rewrite_tokenRef(GrammarAST ID, boolean isRoot, ActionAST argAST) {
		List<SrcOp> ops = delegate.rewrite_tokenRef(ID, isRoot, argAST);
		for (CodeGeneratorExtension ext : extensions) ops = ext.rewrite_tokenRef(ops);
		return ops;
	}

	public List<SrcOp> rewrite_stringRef(GrammarAST ID, boolean isRoot) {
		return rewrite_tokenRef(ID, isRoot, null);
	}

	public OutputModelObject getRoot() { return delegate.getRoot(); }

	public void setRoot(OutputModelObject root) { delegate.setRoot(root); }

	public RuleFunction getCurrentRuleFunction() { return delegate.getCurrentRuleFunction(); }

	public void pushCurrentRule(RuleFunction r) { delegate.pushCurrentRule(r); }

	public RuleFunction popCurrentRule() { return delegate.popCurrentRule(); }

	public Alternative getCurrentAlt() { return delegate.getCurrentAlt(); }

	public void setCurrentAlt(Alternative alt) { delegate.setCurrentAlt(alt); }

	public void setController(OutputModelController controller) { } // nop; we are controller

	public void setCurrentBlock(CodeBlock blk) { delegate.setCurrentBlock(blk); }

	public CodeBlock getCurrentBlock() { return delegate.getCurrentBlock(); }

	public int getCodeBlockLevel() { return delegate.getCodeBlockLevel(); }

	public int getTreeLevel() { return delegate.getTreeLevel(); }

	// SUPPORT

//	public IntervalSet getRewriteElementTokenTypeSet() {
//		IntervalSet elementTokenTypes = new IntervalSet();
//		elementTokenTypes.add(ANTLRParser.TOKEN_REF); // might be imaginary
//		elementTokenTypes.add(ANTLRParser.RULE_REF);
//		elementTokenTypes.add(ANTLRParser.STRING_LITERAL);
//		elementTokenTypes.add(ANTLRParser.LABEL);
//		return elementTokenTypes;
//	}
}
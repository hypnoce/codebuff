package org.antlr.codebuff.walkers;

import org.antlr.codebuff.JavaParser;
import org.antlr.codebuff.Tool;
import org.antlr.codebuff.Trainer;
import org.antlr.codebuff.VisitSiblingLists;
import org.antlr.codebuff.kNNClassifier;
import org.antlr.codebuff.misc.CodeBuffTokenStream;
import org.antlr.codebuff.misc.HashBag;
import org.antlr.codebuff.misc.ParentSiblingListKey;
import org.antlr.codebuff.misc.SiblingListStats;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** [USED IN TRAINING ONLY]
 *  Find subtree roots with repeated child rule subtrees and separators.
 *  Track oversize and regular lists are sometimes treated differently, such as
 *  formal arg lists in Java. Sometimes they are split across lines.
 */
public class CollectSiblingLists extends VisitSiblingLists {
	/** Track set of (parent:alt,child:alt) list pairs and their min,median,variance,max
	 *  but only if the list is all on one line and has a separator.
	 */
	public Map<ParentSiblingListKey, List<Integer>> listInfo = new HashMap<>();

	/** Track set of (parent:alt,child:alt) list pairs and their min,median,variance,max
	 *  but only if the list is split with at least one '\n' before/after
	 *  a separator.
	 */
	public Map<ParentSiblingListKey, List<Integer>> splitListInfo = new HashMap<>();

	public Map<ParentSiblingListKey, List<Integer>> splitListForm = new HashMap<>();

	/** Map token to ("is oversize", element type). Used to compute feature
	 *  vector.
	 */
	Map<Token,Pair<Boolean,Integer>> tokenToListInfo = new HashMap<>();

	CodeBuffTokenStream tokens;

	// reuse object so the maps above fill from multiple files during training
	public void setTokens(CodeBuffTokenStream tokens) {
		this.tokens = tokens;
	}

	public void visitNonSingletonWithSeparator(ParserRuleContext ctx, List<? extends ParserRuleContext> siblings, Token separator) {
		ParserRuleContext first = siblings.get(0);
		ParserRuleContext last = siblings.get(siblings.size()-1);
		List<Token> hiddenToLeft       = tokens.getHiddenTokensToLeft(first.getStart().getTokenIndex());
		List<Token> hiddenToLeftOfSep  = tokens.getHiddenTokensToLeft(separator.getTokenIndex());
		List<Token> hiddenToRightOfSep = tokens.getHiddenTokensToRight(separator.getTokenIndex());
		List<Token> hiddenToRight      = tokens.getHiddenTokensToRight(last.getStop().getTokenIndex());

		Token hiddenTokenToLeft = hiddenToLeft!=null ? hiddenToLeft.get(0) : null;
		Token hiddenTokenToRight = hiddenToRight!=null ? hiddenToRight.get(0) : null;

		int[] ws = new int[4]; // '\n' (before list, before sep, after sep, after last element)
		if ( hiddenTokenToLeft!=null && Tool.count(hiddenTokenToLeft.getText(), '\n')>0 ) {
			ws[0] = '\n';
		}
		if ( hiddenToLeftOfSep!=null && Tool.count(hiddenToLeftOfSep.get(0).getText(), '\n')>0 ) {
			ws[1] = '\n';
			System.out.println("BEFORE "+JavaParser.ruleNames[ctx.getRuleIndex()]+
				                   "->"+JavaParser.ruleNames[ctx.getRuleIndex()]+" sep "+
				                   JavaParser.tokenNames[separator.getType()]+
				                   " "+separator);
		}
		if ( hiddenToRightOfSep!=null && Tool.count(hiddenToRightOfSep.get(0).getText(), '\n')>0 ) {
			ws[2] = '\n';
			System.out.println("AFTER "+JavaParser.ruleNames[ctx.getRuleIndex()]+
				                   "->"+JavaParser.ruleNames[ctx.getRuleIndex()]+" sep "+
				                   JavaParser.tokenNames[separator.getType()]+
				                   " "+separator);
		}
		if ( hiddenTokenToRight!=null && Tool.count(hiddenTokenToRight.getText(), '\n')>0 ) {
			ws[3] = '\n';
		}
		boolean isSplitList = ws[1]=='\n' || ws[2]=='\n';

		// now track length of parent:alt,child:alt list or split-list
		ParentSiblingListKey pair = new ParentSiblingListKey(ctx, first, separator.getType());
		Map<ParentSiblingListKey, List<Integer>> info = isSplitList ? splitListInfo : listInfo;
		List<Integer> lens = info.get(pair);
		if ( lens==null ) {
			lens = new ArrayList<>();
			info.put(pair, lens);
		}
		lens.add(Trainer.getSiblingsLength(siblings));

		// track the form split lists take
		if ( isSplitList ) {
			int form = Trainer.listform(ws);
			List<Integer> forms = splitListForm.get(pair);
			if ( forms==null ) {
				forms = new ArrayList<>();
				splitListForm.put(pair, forms);
			}
			forms.add(form); // track where we put newlines for this list
		}

		// identify the various tokens re list membership

		tokens.seek(first.getStart().getTokenIndex());
		Token prefixToken = tokens.LT(-1); // e.g., '(' in an arg list or ':' in grammar def
		tokenToListInfo.put(prefixToken, new Pair<>(isSplitList, Trainer.LIST_PREFIX));

		List<Tree> separators = getSeparators(ctx, siblings);
		for (Tree s : separators) {
			tokenToListInfo.put((Token)s.getPayload(), new Pair<>(isSplitList, Trainer.LIST_SEPARATOR));
		}

		// handle sibling members
		tokenToListInfo.put(first.getStart(), new Pair<>(isSplitList, Trainer.LIST_FIRST_ELEMENT));
		for (ParserRuleContext s : siblings.subList(1,siblings.size())) {
			tokenToListInfo.put(s.getStart(), new Pair<>(isSplitList, Trainer.LIST_MEMBER));
		}

		tokens.seek(last.getStop().getTokenIndex());
		Token suffixToken = tokens.LT(2);  // e.g., LT(1) is last token of list; LT(2) is ')' in an arg list of ';' in grammar def
		tokenToListInfo.put(suffixToken, new Pair<>(isSplitList, Trainer.LIST_SUFFIX));
	}

	public Map<ParentSiblingListKey, Integer> getSplitListForms() {
		Map<ParentSiblingListKey, Integer> results = new HashMap<>();
		for (ParentSiblingListKey pair : splitListForm.keySet()) {
			HashBag<Integer> votes = new HashBag<>();
			List<Integer> forms = splitListForm.get(pair);
			forms.forEach(votes::add);
			int mostCommonForm = kNNClassifier.getCategoryWithMostVotes(votes);
			results.put(pair, mostCommonForm);
		}
		return results;
	}

	public Map<ParentSiblingListKey, SiblingListStats> getListStats() {
		return getListStats(listInfo);
	}

	public Map<ParentSiblingListKey, SiblingListStats> getSplitListStats() {
		return getListStats(splitListInfo);
	}

	public Map<ParentSiblingListKey, SiblingListStats> getListStats(Map<ParentSiblingListKey, List<Integer>> map) {
		Map<ParentSiblingListKey, SiblingListStats> listSizes = new HashMap<>();
		for (ParentSiblingListKey pair : map.keySet()) {
			List<Integer> lens = map.get(pair);
			Collections.sort(lens);
			int n = lens.size();
			Integer min = lens.get(0);
			Integer median = lens.get(n/2);
			Integer max = lens.get(n-1);
			double var = variance(lens);
			listSizes.put(pair, new SiblingListStats(n, min, median, var, max));
		}
		return listSizes;
	}

	public Map<Token, Pair<Boolean, Integer>> getTokenToListInfo() {
		return tokenToListInfo;
	}

	public static int sum(List<Integer> data) {
		int sum = 0;
		for (int d : data) {
			sum += d;
		}
		return sum;
	}

	public static double variance(List<Integer> data) {
		int n = data.size();
		double sum = 0;
		double avg = sum(data) / ((double)n);
		for (int d : data) {
			sum += (d-avg)*(d-avg);
		}
		return sum / n;
	}

	@Override
	public void visitTerminal(TerminalNode node) {
	}

	@Override
	public void visitErrorNode(ErrorNode node) {
	}

	@Override
	public void exitEveryRule(ParserRuleContext ctx) {
	}
}
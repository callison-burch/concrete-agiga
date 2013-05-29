package edu.jhu.hlt.concrete.agiga;

import edu.jhu.hlt.concrete.Concrete.*;
import edu.jhu.hlt.concrete.Concrete.TokenTagging.TaggedToken;
import edu.jhu.hlt.concrete.util.*;
import edu.jhu.hlt.concrete.io.ProtocolBufferWriter;
import edu.jhu.agiga.*;
import edu.stanford.nlp.trees.*;
import java.util.*;
import java.io.*;

// TODO coref chains
class AgigaConverter {

	public static final String toolName = "Annotated Gigaword Pipeline";
	public static final String corpusName = "Annotated Gigaword";
	public static final double annotationTime = Calendar.getInstance().getTimeInMillis() / 1000d;

	public static AnnotationMetadata metadata() {
		return AnnotationMetadata.newBuilder()
			.setTool(toolName)
			.setTimestamp(annotationTime)
			.setConfidence(1f)
			.build();
	}

	public static String flattenText(AgigaDocument doc) {
		StringBuilder sb = new StringBuilder();
		for(AgigaSentence sent : doc.getSents())
			sb.append(flattenText(sent));
		return sb.toString();
	}

	public static String flattenText(AgigaSentence sent) {
		StringBuilder sb = new StringBuilder();
		for(AgigaToken tok : sent.getTokens())
			sb.append(tok.getWord() + " ");
		return sb.toString().trim();
	}

	public static Parse stanford2concrete(Tree root, Tokenization tokenization) {
		int[] nodeCounter = new int[]{0};
		int left = 0;
		int right = root.getLeaves().size();
		return Parse.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata())
			.setRoot(s2cHelper(root, nodeCounter, left, right, tokenization))
			.build();
	}

	/**
	 * i'm using int[] as a java hack for int* (pass by reference rather than value).
	 */
	private static Parse.Constituent s2cHelper(Tree root, int[] nodeCounter, int left, int right, Tokenization tokenization) {
		assert(nodeCounter.length == 1);
		Parse.Constituent.Builder cb = Parse.Constituent.newBuilder()
			.setId(nodeCounter[0]++)
			.setTag(root.value())
			.setTokenSequence(extractTokenRefSequence(left, right, tokenization));

		HeadFinder hf = new SemanticHeadFinder();
		Tree headTree = hf.determineHead(root);
		int i = 0, headTreeIdx = -1;

		int leftPtr = left;
		for(Tree child : root.getChildrenAsList()) {
			int width = child.getLeaves().size();
			cb.addChild(s2cHelper(child, nodeCounter, leftPtr, leftPtr + width, tokenization));
			leftPtr += width;
			if(child == headTree) {
				assert(headTreeIdx < 0);
				headTreeIdx = i;
			}
			i++;
		}
		assert(leftPtr == right);

		if(headTreeIdx >= 0)
			cb.setHeadChildIndex(headTreeIdx);

		return cb.build();
	}

	public static TokenRefSequence extractTokenRefSequence(int left, int right, Tokenization tokenization) {
		assert(left < right && left >= 0 && right <= tokenization.getTokenList().size());
		assert(tokenization.getKind() == Tokenization.Kind.TOKEN_LIST);
		TokenRefSequence.Builder tb = TokenRefSequence.newBuilder()
			.setTokenization(tokenization.getUuid());
		for(int i=left; i<right; i++)
			tb.addTokenId(tokenization.getToken(i).getTokenId());
		return tb.build();
	}

	public static TokenRef extractTokenRef(int index, Tokenization tokenization) {
		assert(index >= 0);
		assert(tokenization.getKind() == Tokenization.Kind.TOKEN_LIST);
		int tokId = tokenization.getToken(index).getTokenId();
		return TokenRef.newBuilder()
			.setTokenId(tokId)
			.setTokenization(tokenization.getUuid())
			.build();
	}


	/**
	 * name is the type of dependencies, e.g. "col-deps" or "col-ccproc-deps"
	 */
	public static DependencyParse convertDependencyParse(List<AgigaTypedDependency> deps, String name, Tokenization tokenization) {
		DependencyParse.Builder db = DependencyParse.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata());
		for(AgigaTypedDependency ad : deps) {
			
			DependencyParse.Dependency.Builder depB = DependencyParse.Dependency.newBuilder()
				.setDep(extractTokenRef(ad.getDepIdx(), tokenization))
				.setEdgeType(ad.getType());

			if(ad.getGovIdx() >= 0)	// else ROOT
				depB.setGov(extractTokenRef(ad.getGovIdx(), tokenization));

			db.addDependency(depB.build());
		}
		return db.build();
	}

	public static Tokenization convertTokenization(AgigaSentence sent) {

		TokenTagging.Builder lemmaBuilder = TokenTagging.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata());

		TokenTagging.Builder posBuilder = TokenTagging.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata());

		TokenTagging.Builder nerBuilder = TokenTagging.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata());

		//TokenTagging.Builder normNerBuilder = TokenTagging.newBuilder()
		//	.setUuid(IdUtil.generateUUID())
		//	.setMetadata(metadata());

		Tokenization.Builder tb = Tokenization.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata())
			.setKind(Tokenization.Kind.TOKEN_LIST);
		int charOffset = 0;
		int tokId = 0;
		for(AgigaToken tok : sent.getTokens()) {

			int curTokId = tokId++;

			// token
			tb.addToken(Token.newBuilder()
				.setTokenId(curTokId)
				.setText(tok.getWord())
				.setTextSpan(TextSpan.newBuilder()
					.setStart(charOffset)
					.setEnd(charOffset + tok.getWord().length())
					.build())
				.build());

			// token annotations
			lemmaBuilder.addTaggedToken(makeTaggedToken(tok.getLemma(), curTokId));
			posBuilder.addTaggedToken(makeTaggedToken(tok.getPosTag(), curTokId));
			nerBuilder.addTaggedToken(makeTaggedToken(tok.getNerTag(), curTokId));
			//normNerBuilder.addTaggedToken(makeTaggedToken(tok.getNormNerTag(), curTokId));

			charOffset += tok.getWord().length() + 1;
		}
		return tb
			.addLemmas(posBuilder.build())
			.addPosTags(posBuilder.build())
			.addNerTags(posBuilder.build())
			.build();
	}

	public static TaggedToken makeTaggedToken(String tag, int tokId) {
		return TaggedToken.newBuilder()
			.setTokenId(tokId)
			.setTag(tag)
			.setConfidence(1f)
			.build();
	}

	public static Sentence convertSentence(AgigaSentence sent) {
		Tokenization tokenization = convertTokenization(sent);
		return Sentence.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setTextSpan(TextSpan.newBuilder()
				.setStart(0)
				.setEnd(flattenText(sent).length())
				.build())
			// tokenization
			.addTokenization(tokenization)
			// parses
			.addParse(stanford2concrete(sent.getStanfordContituencyTree(), tokenization))
			.addDependencyParse(convertDependencyParse(sent.getBasicDeps(), "basic-deps", tokenization))
			.addDependencyParse(convertDependencyParse(sent.getColDeps(), "col-deps", tokenization))
			.addDependencyParse(convertDependencyParse(sent.getColCcprocDeps(), "col-ccproc-deps", tokenization))
			.build();
	}

	public static SentenceSegmentation sentenceSegment(AgigaDocument doc) {
		SentenceSegmentation.Builder sb = SentenceSegmentation.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata());
		for(AgigaSentence sentence : doc.getSents())
			sb = sb.addSentence(convertSentence(sentence));
		return sb.build();
	}

	public static SectionSegmentation sectionSegment(AgigaDocument doc, String rawText) {
		return SectionSegmentation.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setMetadata(metadata())
			.addSection(Section.newBuilder()
				.setUuid(IdUtil.generateUUID())
				.setTextSpan(TextSpan.newBuilder()
					.setStart(0)
					.setEnd(rawText.length())
					.build())
				.addSentenceSegmentation(sentenceSegment(doc))
				.build())
			.build();
	}

	public static Communication convertDoc(AgigaDocument doc, KnowledgeGraph kg) {
		CommunicationGUID guid = CommunicationGUID.newBuilder()
			.setCorpusName(corpusName)
			.setCommunicationId(doc.getDocId())
			.build();
		String flatText = flattenText(doc);
		return Communication.newBuilder()
			.setUuid(IdUtil.generateUUID())
			.setGuid(guid)
			.setText(flatText)
			.addSectionSegmentation(sectionSegment(doc, flatText))
			.setKind(Communication.Kind.NEWS)
			.setKnowledgeGraph(kg)
			.build();
	}


	// need some code that reads agiga docs, converts, and then dumps them into a file
	public static void main(String[] args) throws Exception {
		assert(false);
		if(args.length != 2) {
			System.out.println("please provide:");
			System.out.println("1) an input Agiga XML file");
			System.out.println("2) an output Concrete Protobuf file");
			return;
		}
		long start = System.currentTimeMillis();
		File agigaXML = new File(args[0]);	assert(agigaXML.exists() && agigaXML.isFile());
		File output = new File(args[1]);
		StreamingDocumentReader docReader = new StreamingDocumentReader(agigaXML.getPath(), new AgigaPrefs());
		ProtocolBufferWriter writer = new ProtocolBufferWriter(new FileOutputStream(output));

		// TODO we need a knowledge graph
		KnowledgeGraph kg = new ProtoFactory(9001).generateKnowledgeGraph();
		writer.write(kg);

		int c = 0;
		for(AgigaDocument doc : docReader) {
			Communication comm = convertDoc(doc, kg);
			writer.write(comm);
			c++;
		}
		writer.close();
		System.out.printf("done, wrote %d communications to %s in %.1f seconds\n",
			c, output.getPath(), (System.currentTimeMillis() - start)/1000d);
	}
}


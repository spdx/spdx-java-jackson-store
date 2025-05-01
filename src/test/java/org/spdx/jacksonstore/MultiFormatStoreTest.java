/**
 * Copyright (c) 2020 Source Auditor Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.spdx.jacksonstore;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.spdx.core.DefaultModelStore;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.core.ModelRegistry;
import org.spdx.jacksonstore.MultiFormatStore.Format;
import org.spdx.jacksonstore.MultiFormatStore.Verbose;
import org.spdx.library.LicenseInfoFactory;
import org.spdx.library.ModelCopyManager;
import org.spdx.library.model.v2.Checksum;
import org.spdx.library.model.v2.Relationship;
import org.spdx.library.model.v2.SpdxConstantsCompatV2;
import org.spdx.library.model.v2.SpdxDocument;
import org.spdx.library.model.v2.SpdxElement;
import org.spdx.library.model.v2.SpdxFile;
import org.spdx.library.model.v2.SpdxModelFactoryCompatV2;
import org.spdx.library.model.v2.SpdxModelInfoV2_X;
import org.spdx.library.model.v2.SpdxPackage;
import org.spdx.library.model.v2.Version;
import org.spdx.library.model.v2.enumerations.ChecksumAlgorithm;
import org.spdx.library.model.v2.enumerations.Purpose;
import org.spdx.library.model.v2.enumerations.RelationshipType;
import org.spdx.library.model.v2.license.AnyLicenseInfo;
import org.spdx.library.model.v2.license.SpdxNoAssertionLicense;
import org.spdx.library.model.v3_0_1.SpdxModelInfoV3_0;
import org.spdx.storage.IModelStore;
import org.spdx.storage.ISerializableModelStore;
import org.spdx.storage.simple.InMemSpdxStore;
import org.spdx.utility.compare.SpdxCompareException;
import org.spdx.utility.compare.SpdxComparer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.fge.jackson.JsonLoader;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;

import junit.framework.TestCase;

/**
 * @author Gary O'Neall
 *
 */
public class MultiFormatStoreTest extends TestCase {
	
	static final String JSON_FILE_PATH = "testResources" + File.separator + "SPDXJSONExample-v2.3.spdx.json";
	static final String JSON_2_2_FILE_PATH = "testResources" + File.separator + "SPDXJSONExample-v2.2.spdx.json";
	// This is a copy of SPDXJSONExample-v2.2.spdx.json with relationships property renamed to relationship
	static final String SINGULAR_RELATIONSHIP_FILE_PATH = "testResources" + File.separator + "SingularRelationship.json";
	// This is a copy of SPDXJSONExample-v2.2.spdx.json with duplicate hasFile/CONTAINS relationships and duplicate documentDescribes/DESCRIBES relationship
	static final String JSON_WITH_DUPLICATES_FILE_PATH = "testResources" + File.separator + "duplicated.json";
	static final String JSON_NO_HAS_FILES_FILE_PATH = "testResources" + File.separator + "noHasFilesDescribes.json";
	static final String XML_1REL_FILE_PATH = "testResources" + File.separator + "SPDXXML-SingleRel-v2.3.spdx.xml";
	static final String JSON_SCHEMA_V2_3 = "testResources" + File.separator + "spdx-schema.json";
	static final String UNSORTED_JSON = "testResources" + File.separator + "SPDXJSONUnsortedIds.json";
	static final String UNSORTED_RELATIONSHIPS = "testResources" + File.separator + "unsortedrelationships.json";

	/* (non-Javadoc)
	 * @see junit.framework.TestCase#setUp()
	 */
	protected void setUp() throws Exception {
		super.setUp();
		ModelRegistry.getModelRegistry().registerModel(new SpdxModelInfoV2_X());
		ModelRegistry.getModelRegistry().registerModel(new SpdxModelInfoV3_0());
		DefaultModelStore.initialize(new InMemSpdxStore(), "http://test/doc", new ModelCopyManager());
	}

	/* (non-Javadoc)
	 * @see junit.framework.TestCase#tearDown()
	 */
	protected void tearDown() throws Exception {
		super.tearDown();
	}

	/**
	 * Test method for {@link org.spdx.jacksonstore.MultiFormatStore#serialize(java.lang.String, java.io.OutputStream)} and {@link org.spdx.jacksonstore.MultiFormatStore#deSerialize(java.io.InputStream)}.
	 * @throws IOException 
	 * @throws InvalidSPDXAnalysisException 
	 * @throws SpdxCompareException 
	 */
	public void testDeSerializeSerializeJson() throws InvalidSPDXAnalysisException, IOException, SpdxCompareException {
		File jsonFile = new File(JSON_FILE_PATH);
		MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		SpdxDocument inputDocument;
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputDocument = inputStore.deSerialize(input, false);
		}
		String documentUri = inputStore.getDocumentUris().toArray(new String[inputStore.getDocumentUris().size()])[0];
		assertEquals(documentUri, inputDocument.getDocumentUri());
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		// test Overwrite
		try (InputStream input = new FileInputStream(jsonFile)) {
			try {
				inputStore.deSerialize(input, false);
				fail("Input was overwritten when overwrite was set to false");
			} catch(InvalidSPDXAnalysisException ex) {
				// expected
			}
		}
		// Deserialize
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		inputStore.serialize(outputStream);
		ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
		MultiFormatStore outputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		outputStore.deSerialize(inputStream, false);
		SpdxDocument compareDocument = new SpdxDocument(outputStore, documentUri, null, false);
		verify = inputDocument.verify();
		assertEquals(0, verify.size());
		verify = compareDocument.verify();
		assertEquals(0, verify.size());
		SpdxComparer comparer = new SpdxComparer();
		comparer.compare(inputDocument, compareDocument);
		assertTrue(comparer.isfilesEquals());
		assertTrue(comparer.isPackagesEquals());
		assertTrue(comparer.isDocumentRelationshipsEquals());
		assertFalse(comparer.isDifferenceFound());
		assertTrue(inputDocument.equivalent(compareDocument));
	}
	
	public void testDeserialize2point3Fields() throws InvalidSPDXAnalysisException, IOException, SpdxCompareException {
		File jsonFile = new File(JSON_FILE_PATH);
		MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputStore.deSerialize(input, false);
		}
		String documentUri = inputStore.getDocumentUris().toArray(new String[inputStore.getDocumentUris().size()])[0];
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		
		SpdxPackage pkg = new SpdxPackage(inputStore, documentUri, "SPDXRef-Package", null, false);
		// Hash algorithms with dash
		Checksum blake2b = null;
		for (Checksum checksum:pkg.getChecksums()) {
			if (checksum.getAlgorithm().equals(ChecksumAlgorithm.BLAKE2b_512)) {
				blake2b = checksum;
				break;
			}
		}
		assertTrue(Objects.nonNull(blake2b));
		assertEquals(blake2b.getValue(), "a8cfbbd73726062df0c6864dda65defe58ef0cc52a5625090fa17601e1eecd1b628e94f396ae402a00acc9eab77b4d4c2e852aaaa25a636d80af3fc7913ef5b8");
		// primary purpose
		Optional<Purpose> primaryPurpose = pkg.getPrimaryPurpose();
		assertEquals(Optional.of(Purpose.CONTAINER), primaryPurpose);
		// Not required license fields
		SpdxPackage pkg2 = new SpdxPackage(inputStore, documentUri, "SPDXRef-fromDoap-1", null, false);
		String copyright = pkg2.getCopyrightText();
		assertTrue(copyright.isEmpty());
		AnyLicenseInfo concluded = pkg2.getLicenseConcluded();
		assertEquals(new SpdxNoAssertionLicense(), concluded);
		AnyLicenseInfo declared = pkg2.getLicenseDeclared();
		assertEquals(new SpdxNoAssertionLicense(), declared);
		// release date
		assertEquals(Optional.of("2011-02-10T00:00:00Z"), pkg.getReleaseDate());
		// built date
		assertEquals(Optional.of("2012-02-10T00:00:00Z"), pkg.getBuiltDate());
		// valid until
		assertEquals(Optional.of("2013-02-10T00:00:00Z"), pkg.getValidUntilDate());
		// relationship specification for
		SpdxFile file = new SpdxFile(inputStore, documentUri, "SPDXRef-DoapSource", null, false);
		assertEquals(2, file.getRelationships().size());
		boolean foundSpecFor = false;
		boolean foundReqFor = false;
		for (Relationship rel:file.getRelationships()) {
			if (rel.getRelationshipType().equals(RelationshipType.SPECIFICATION_FOR)) {
				foundSpecFor = true;
			}
			if (rel.getRelationshipType().equals(RelationshipType.REQUIREMENT_DESCRIPTION_FOR)) {
				foundReqFor = true;
			}
		}
		assertTrue(foundSpecFor);
		assertTrue(foundReqFor);
		// relationship requirement description for
		// hasFiles
		assertEquals(3, pkg.getFiles().size());
	}
	
	public void testDeSerializeSerializeYaml() throws InvalidSPDXAnalysisException, IOException, SpdxCompareException {
		File jsonFile = new File(JSON_FILE_PATH);
		MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputStore.deSerialize(input, false);
		}
		String documentUri = inputStore.getDocumentUris().toArray(new String[inputStore.getDocumentUris().size()])[0];
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		
		// Deserialize
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		inputStore.setFormat(Format.YAML);
		inputStore.serialize(outputStream);
		ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
		MultiFormatStore outputStore = new MultiFormatStore(new InMemSpdxStore(), Format.YAML);
		outputStore.deSerialize(inputStream, false);
		SpdxDocument compareDocument = new SpdxDocument(outputStore, documentUri, null, false);
		verify = inputDocument.verify();
		assertEquals(0, verify.size());
		verify = compareDocument.verify();
		assertEquals(0, verify.size());
		SpdxComparer comparer = new SpdxComparer();
		comparer.compare(inputDocument, compareDocument);
		assertTrue(comparer.isfilesEquals());
		assertTrue(comparer.isPackagesEquals());
		assertTrue(comparer.isDocumentRelationshipsEquals());
		assertFalse(comparer.isDifferenceFound());
		assertTrue(inputDocument.equivalent(compareDocument));
	}

	public void testDeSerializeSerializeXml() throws InvalidSPDXAnalysisException, IOException, SpdxCompareException {
		File jsonFile = new File(JSON_FILE_PATH);
		MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputStore.deSerialize(input, false);
		}
		String documentUri = inputStore.getDocumentUris().toArray(new String[inputStore.getDocumentUris().size()])[0];
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		
		// Deserialize
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		inputStore.setFormat(Format.XML);
		inputStore.serialize(outputStream);
		@SuppressWarnings("unused")
		String temp = new String(outputStream.toByteArray());
		ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
		MultiFormatStore outputStore = new MultiFormatStore(new InMemSpdxStore(), Format.XML);
		outputStore.deSerialize(inputStream, false);
		SpdxDocument compareDocument = new SpdxDocument(outputStore, documentUri, null, false);
		verify = inputDocument.verify();
		assertEquals(0, verify.size());
		verify = compareDocument.verify();
		assertEquals(0, verify.size());
		SpdxComparer comparer = new SpdxComparer();
		comparer.compare(inputDocument, compareDocument);
		assertTrue(comparer.isExtractedLicensingInfosEqual());
		assertTrue(comparer.isfilesEquals());
		assertTrue(comparer.isPackagesEquals());
		assertTrue(comparer.isDocumentRelationshipsEquals());
		assertFalse(comparer.isDifferenceFound());
		assertTrue(inputDocument.equivalent(compareDocument));
	}
	
	// Test for issue #21 Validation accepts invalid SPDX YAML files
	public void testSingularRelationship() throws FileNotFoundException, IOException, InvalidSPDXAnalysisException {
	    File jsonFile = new File(SINGULAR_RELATIONSHIP_FILE_PATH);
        MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
        try (InputStream input = new FileInputStream(jsonFile)) {
            inputStore.deSerialize(input, false);
            fail("Singular relationship property should not succeed");
        } catch(InvalidSPDXAnalysisException ex) {
            // expected
        }
	}
	
	public void testDuplicates() throws FileNotFoundException, IOException, InvalidSPDXAnalysisException, SpdxCompareException {
		File jsonFile = new File(JSON_2_2_FILE_PATH);
		MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputStore.deSerialize(input, false);
		}
		String documentUri = inputStore.getDocumentUris().toArray(new String[inputStore.getDocumentUris().size()])[0];
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		
		File jsonFileWithDuplicates = new File(JSON_WITH_DUPLICATES_FILE_PATH);
		MultiFormatStore compareStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonFileWithDuplicates)) {
			compareStore.deSerialize(input, false);
		}
		SpdxDocument compareDocument = new SpdxDocument(compareStore, documentUri, null, false);
		verify = compareDocument.verify();
		assertEquals(0, verify.size());
		
		SpdxComparer comparer = new SpdxComparer();
		comparer.compare(inputDocument, compareDocument);
		assertTrue(comparer.isfilesEquals());
		assertTrue(comparer.isPackagesEquals());
		assertTrue(comparer.isDocumentRelationshipsEquals());
		assertFalse(comparer.isDifferenceFound());
		assertTrue(inputDocument.equivalent(compareDocument));
	}
	
	public void testNoHasFiles() throws FileNotFoundException, IOException, InvalidSPDXAnalysisException, SpdxCompareException {
		File jsonFile = new File(JSON_2_2_FILE_PATH);
		MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputStore.deSerialize(input, false);
		}
		String documentUri = inputStore.getDocumentUris().toArray(new String[inputStore.getDocumentUris().size()])[0];
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		
		File jsonNoHasFiles = new File(JSON_NO_HAS_FILES_FILE_PATH);
		MultiFormatStore compareStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonNoHasFiles)) {
			compareStore.deSerialize(input, false);
		}
		SpdxDocument compareDocument = new SpdxDocument(compareStore, documentUri, null, false);
		verify = compareDocument.verify();
		assertEquals(0, verify.size());
		
		SpdxComparer comparer = new SpdxComparer();
		comparer.compare(inputDocument, compareDocument);
		assertTrue(comparer.isfilesEquals());
		assertTrue(comparer.isPackagesEquals());
		assertTrue(comparer.isDocumentRelationshipsEquals());
		assertFalse(comparer.isDifferenceFound());
		assertTrue(inputDocument.equivalent(compareDocument));
	}
	
	/**
	 * Test if relationships properly serialize relationship comments
	 * @throws InvalidSPDXAnalysisException
	 * @throws IOException 
	 */
	public void testRelationshipComment() throws InvalidSPDXAnalysisException, IOException {
		String documentUri = "https://someuri";
        ModelCopyManager copyManager = new ModelCopyManager();
        ISerializableModelStore modelStore = new MultiFormatStore(new InMemSpdxStore(), MultiFormatStore.Format.JSON_PRETTY);
        SpdxDocument document = SpdxModelFactoryCompatV2.createSpdxDocumentV2(modelStore, documentUri, copyManager);
        document.setSpecVersion(Version.TWO_POINT_THREE_VERSION);
        document.setName("SPDX-tool-test");
        Checksum sha1Checksum = Checksum.create(modelStore, documentUri, ChecksumAlgorithm.SHA1, "d6a770ba38583ed4bb4525bd96e50461655d2758");
        AnyLicenseInfo concludedLicense = LicenseInfoFactory.parseSPDXLicenseStringCompatV2("LGPL-2.0-only OR LicenseRef-2");
        SpdxFile fileA = document.createSpdxFile("SPDXRef-fileA", "./package/fileA.c", concludedLicense,
                        Arrays.asList(new AnyLicenseInfo[0]), "Copyright 2008-2010 John Smith", sha1Checksum)
                .build();
        String relationshipComment = "Relationship comment";
        Relationship relationship = document.createRelationship(fileA, RelationshipType.CONTAINS, relationshipComment);
        document.addRelationship(relationship);
        Collection<Relationship> docrels = document.getRelationships();
        assertEquals(1, docrels.size());
        for (Relationship rel:docrels) {
        	assertEquals(RelationshipType.CONTAINS, rel.getRelationshipType());
        	SpdxElement elem = rel.getRelatedSpdxElement().get();
        	assertEquals(fileA, elem);
        	Optional<String> relComment = rel.getComment();
        	assertTrue(relComment.isPresent());
        	assertEquals(relationshipComment, relComment.get());
        }
    	
    	// test that it deserializes correctly
    	Path tempDirPath = Files.createTempDirectory("mfsTest");
    	File serFile = tempDirPath.resolve("testspdx.json").toFile();
    	assertTrue(serFile.createNewFile());
    	try {
    		try (OutputStream stream = new FileOutputStream(serFile)) {
    			modelStore.serialize(stream);
    		}
    		ISerializableModelStore resultStore = new MultiFormatStore(new InMemSpdxStore(), MultiFormatStore.Format.JSON);
    		try (InputStream inStream = new FileInputStream(serFile)) {
    			resultStore.deSerialize(inStream, false);
				List<String> restoredDocUris = getDocUris(resultStore);
				assertEquals(1, restoredDocUris.size());
				assertEquals(documentUri, restoredDocUris.get(0));
    		}
    		document = SpdxModelFactoryCompatV2.createSpdxDocumentV2(resultStore, documentUri, copyManager);
    		docrels = document.getRelationships();
            assertEquals(1, docrels.size());
            for (Relationship rel:docrels) {
            	assertEquals(RelationshipType.CONTAINS, rel.getRelationshipType());
            	SpdxElement elem = rel.getRelatedSpdxElement().get();
            	assertEquals(fileA, elem);
            	Optional<String> relComment = rel.getComment();
            	assertTrue(relComment.isPresent());
            	assertEquals(relationshipComment, relComment.get());
            }
    		
    		JsonNode doc;
    		
    		try (InputStream inStream = new FileInputStream(serFile)) {
    			ObjectMapper inputMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    			doc = inputMapper.readTree(inStream);
    		}
    		
    		JsonNode relationshipsNode = doc.get("relationships");
    		Iterator<JsonNode> iter = relationshipsNode.elements();
    		int count = 0;
    		
			while (iter.hasNext()) {
	            while (iter.hasNext()) {
	            	count++;
	            	JsonNode relationshipNode = iter.next();
	            	assertEquals("SPDXRef-DOCUMENT", relationshipNode.get("spdxElementId").asText());
	            	assertEquals(fileA.getId(), relationshipNode.get("relatedSpdxElement").asText());
	            	assertEquals(RelationshipType.CONTAINS.toString(), relationshipNode.get("relationshipType").asText());
	            	assertEquals(relationshipComment, relationshipNode.get(SpdxConstantsCompatV2.RDFS_PROP_COMMENT.getName()).asText());
	            	
	            }
	            assertEquals(1, count);
            }
    	} finally {
    		if (serFile.exists()) {
    			serFile.delete();
    		}
    		tempDirPath.toFile().delete();
    	}
	}
	
	/**			
	 * Test if the DocumentDescribes relationship produces more than one relationship
	 * see issue #115 for context
	 * @throws InvalidSPDXAnalysisException
	 * @throws IOException 
	 */
	public void testDocumentDescribes() throws InvalidSPDXAnalysisException, IOException {
		String documentUri = "https://someuri";
        ModelCopyManager copyManager = new ModelCopyManager();
        ISerializableModelStore modelStore = new MultiFormatStore(new InMemSpdxStore(), MultiFormatStore.Format.JSON_PRETTY);
        SpdxDocument document = SpdxModelFactoryCompatV2.createSpdxDocumentV2(modelStore, documentUri, copyManager);
        document.setSpecVersion(Version.TWO_POINT_THREE_VERSION);
        document.setName("SPDX-tool-test");
        Checksum sha1Checksum = Checksum.create(modelStore, documentUri, ChecksumAlgorithm.SHA1, "d6a770ba38583ed4bb4525bd96e50461655d2758");
        AnyLicenseInfo concludedLicense = LicenseInfoFactory.parseSPDXLicenseStringCompatV2("LGPL-2.0-only OR LicenseRef-2");
        SpdxFile fileA = document.createSpdxFile("SPDXRef-fileA", "./package/fileA.c", concludedLicense,
                        Arrays.asList(new AnyLicenseInfo[0]), "Copyright 2008-2010 John Smith", sha1Checksum)
                .build();
        SpdxFile fileB = document.createSpdxFile("SPDXRef-fileB", "./package/fileB.c", concludedLicense,
        		Arrays.asList(new AnyLicenseInfo[0]), "Copyright 2008-2010 John Smith", sha1Checksum)
                .build();
        document.getDocumentDescribes().addAll(Arrays.asList(new SpdxElement[] {fileA, fileB}));
        assertEquals(2, document.getDocumentDescribes().size());
        assertTrue(document.getDocumentDescribes().contains(fileA));
        assertTrue(document.getDocumentDescribes().contains(fileB));
        Collection<Relationship> docrels = document.getRelationships();
        assertEquals(2, docrels.size());
        boolean foundFileA = false;
        boolean foundFileB = false;
        for (Relationship rel:docrels) {
        	assertEquals(RelationshipType.DESCRIBES, rel.getRelationshipType());
        	SpdxElement elem = rel.getRelatedSpdxElement().get();
        	if (fileA.equals(elem)) {
        		foundFileA = true;
        	} else if (fileB.equals(elem)) {
        		foundFileB = true;
        	} else {
        		fail("Unexpected relationship");
        	}
        }
    	assertTrue(foundFileA);
    	assertTrue(foundFileB);
    	// test that it deserializes correctly
    	Path tempDirPath = Files.createTempDirectory("mfsTest");
    	File serFile = tempDirPath.resolve("testspdx.json").toFile();
    	assertTrue(serFile.createNewFile());
    	try {
    		try (OutputStream stream = new FileOutputStream(serFile)) {
    			modelStore.serialize(stream);
    		}
    		ISerializableModelStore resultStore = new MultiFormatStore(new InMemSpdxStore(), MultiFormatStore.Format.JSON);
    		try (InputStream inStream = new FileInputStream(serFile)) {
    			resultStore.deSerialize(inStream, false);
				List<String> restoredDocUris = getDocUris(resultStore);
				assertEquals(1, restoredDocUris.size());
				assertEquals(documentUri, restoredDocUris.get(0));
    		}
    		document = SpdxModelFactoryCompatV2.createSpdxDocumentV2(resultStore, documentUri, copyManager);
    		assertEquals(2, document.getDocumentDescribes().size());
            assertTrue(document.getDocumentDescribes().contains(fileA));
            assertTrue(document.getDocumentDescribes().contains(fileB));
            docrels = document.getRelationships();
            assertEquals(2, docrels.size());
            foundFileA = false;
            foundFileB = false;
            for (Relationship rel:docrels) {
            	assertEquals(RelationshipType.DESCRIBES, rel.getRelationshipType());
            	SpdxElement elem = rel.getRelatedSpdxElement().get();
            	if (fileA.equals(elem)) {
            		foundFileA = true;
            	} else if (fileB.equals(elem)) {
            		foundFileB = true;
            	} else {
            		fail("Unexpected relationship");
            	}
            }
        	assertTrue(foundFileA);
        	assertTrue(foundFileB);
    		JsonNode doc;
    		
    		try (InputStream inStream = new FileInputStream(serFile)) {
    			ObjectMapper inputMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    			doc = inputMapper.readTree(inStream);
    		}
    		JsonNode describes = doc.get("documentDescribes");
    		assertTrue(Objects.isNull(describes));
    	} finally {
    		if (serFile.exists()) {
    			serFile.delete();
    		}
    		tempDirPath.toFile().delete();
    	}
	}
	
	/**			
	 * Test if the hasFiles relationship produces more than one relationship
	 * see issue #115 for context
	 * @throws InvalidSPDXAnalysisException
	 * @throws IOException 
	 */
	public void testhasFiles() throws InvalidSPDXAnalysisException, IOException {
		String documentUri = "https://someuri";
        ModelCopyManager copyManager = new ModelCopyManager();
        ISerializableModelStore modelStore = new MultiFormatStore(new InMemSpdxStore(), MultiFormatStore.Format.JSON_PRETTY);
        SpdxDocument document = SpdxModelFactoryCompatV2.createSpdxDocumentV2(modelStore, documentUri, copyManager);
        document.setSpecVersion(Version.TWO_POINT_THREE_VERSION);
        document.setName("SPDX-tool-test");
        Checksum sha1Checksum = Checksum.create(modelStore, documentUri, ChecksumAlgorithm.SHA1, "d6a770ba38583ed4bb4525bd96e50461655d2758");
        AnyLicenseInfo concludedLicense = LicenseInfoFactory.parseSPDXLicenseStringCompatV2("LGPL-2.0-only OR LicenseRef-2");
        SpdxFile fileA = document.createSpdxFile("SPDXRef-fileA", "./package/fileA.c", concludedLicense,
                        Arrays.asList(new AnyLicenseInfo[0]), "Copyright 2008-2010 John Smith", sha1Checksum)
                .build();
        SpdxFile fileB = document.createSpdxFile("SPDXRef-fileB", "./package/fileB.c", concludedLicense,
        		Arrays.asList(new AnyLicenseInfo[0]), "Copyright 2008-2010 John Smith", sha1Checksum)
                .build();
        SpdxPackage pkg = document.createPackage("SPDXRef-package", "package name", concludedLicense, "NOASSERTION", concludedLicense)
        		.setDownloadLocation("NOASSERTION")
        		.setFilesAnalyzed(false)
        		.build();
        document.getDocumentDescribes().add(pkg);
        pkg.getFiles().add(fileA);
        pkg.getFiles().add(fileB);
        assertEquals(2, pkg.getFiles().size());
        assertTrue(pkg.getFiles().contains(fileA));
        assertTrue(pkg.getFiles().contains(fileB));
        Collection<Relationship> pkgrels = pkg.getRelationships();
        assertEquals(2, pkgrels.size());
        boolean foundFileA = false;
        boolean foundFileB = false;
        for (Relationship rel:pkgrels) {
        	assertEquals(RelationshipType.CONTAINS, rel.getRelationshipType());
        	SpdxElement elem = rel.getRelatedSpdxElement().get();
        	if (fileA.equals(elem)) {
        		foundFileA = true;
        	} else if (fileB.equals(elem)) {
        		foundFileB = true;
        	} else {
        		fail("Unexpected relationship");
        	}
        }
    	assertTrue(foundFileA);
    	assertTrue(foundFileB);
    	// test that it deserializes correctly
    	Path tempDirPath = Files.createTempDirectory("mfsTest");
    	File serFile = tempDirPath.resolve("testspdx2.json").toFile();
    	assertTrue(serFile.createNewFile());
    	try {
    		try (OutputStream stream = new FileOutputStream(serFile)) {
    			modelStore.serialize(stream);
    		}
    		ISerializableModelStore resultStore = new MultiFormatStore(new InMemSpdxStore(), MultiFormatStore.Format.JSON);
    		try (InputStream inStream = new FileInputStream(serFile)) {
    			resultStore.deSerialize(inStream, false);
				List<String> restoredDocUris = getDocUris(resultStore);
				assertEquals(1, restoredDocUris.size());
				assertEquals(documentUri, restoredDocUris.get(0));
    		}
    		document = SpdxModelFactoryCompatV2.createSpdxDocumentV2(resultStore, documentUri, copyManager);
    		pkg = (SpdxPackage)document.getDocumentDescribes().toArray(new SpdxElement[1])[0];
    		
    		assertEquals(2, pkg.getFiles().size());
            assertTrue(pkg.getFiles().contains(fileA));
            assertTrue(pkg.getFiles().contains(fileB));
            pkgrels = pkg.getRelationships();
            assertEquals(2, pkgrels.size());
            foundFileA = false;
            foundFileB = false;
            for (Relationship rel:pkgrels) {
            	assertEquals(RelationshipType.CONTAINS, rel.getRelationshipType());
            	SpdxElement elem = rel.getRelatedSpdxElement().get();
            	if (fileA.equals(elem)) {
            		foundFileA = true;
            	} else if (fileB.equals(elem)) {
            		foundFileB = true;
            	} else {
            		fail("Unexpected relationship");
            	}
            }
        	assertTrue(foundFileA);
        	assertTrue(foundFileB);
        	
    		JsonNode doc;
    		try (InputStream inStream = new FileInputStream(serFile)) {
    			ObjectMapper inputMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    			doc = inputMapper.readTree(inStream);
    		}
    		JsonNode packages = doc.get("packages");
    		JsonNode jsonPkg = packages.elements().next();
    		JsonNode files = jsonPkg.get("hasFiles");
    		assertTrue(Objects.isNull(files));
    	} finally {
    		if (serFile.exists()) {
    			serFile.delete();
    		}
    		tempDirPath.toFile().delete();
    	}
	}

	public void testDeSerializeXml_singleRelationship() throws InvalidSPDXAnalysisException, IOException {
		File xmlFile = new File(XML_1REL_FILE_PATH);
		IModelStore modelStore = new InMemSpdxStore();
		MultiFormatStore inputStore = new MultiFormatStore(modelStore, Format.XML, Verbose.COMPACT);
		try (InputStream in = new BufferedInputStream(Files.newInputStream(xmlFile.toPath()))) {
			inputStore.deSerialize(in, false);
		}
		String documentUri = inputStore.getDocumentUris().toArray(new String[inputStore.getDocumentUris().size()])[0];
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
	}
	
	/**
	 * Test serialized json validates
	 * @throws IOException 
	 * @throws InvalidSPDXAnalysisException 
	 * @throws SpdxCompareException 
	 * @throws ProcessingException 
	 */
	public void testSerializeJson() throws InvalidSPDXAnalysisException, IOException, SpdxCompareException, ProcessingException {
		File jsonFile = new File(JSON_FILE_PATH);
		MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputStore.deSerialize(input, false);
		}
		String documentUri = inputStore.getDocumentUris().toArray(new String[inputStore.getDocumentUris().size()])[0];
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		// Add a purpose of operating system to make sure the underscore is preserved
		SpdxPackage pkg = inputDocument.createPackage(SpdxConstantsCompatV2.SPDX_ELEMENT_REF_PRENUM + "-purpose", 
				"Package with a Purpose", new SpdxNoAssertionLicense(), "NoAssertion", 
				new SpdxNoAssertionLicense())
				.setPrimaryPurpose(Purpose.OPERATING_SYSTEM)
				.setDownloadLocation("NOASSERTION")
				.setFilesAnalyzed(false)
				.build();
		inputDocument.addRelationship(inputDocument.createRelationship(pkg, RelationshipType.DESCRIBES, "Describe another package"));
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		// test that it deserializes correctly
		Path tempDirPath = Files.createTempDirectory("mfsTest2");
		File serFile = tempDirPath.resolve("testspdx.json").toFile();
		assertTrue(serFile.createNewFile());
		try {
			try (OutputStream stream = new FileOutputStream(serFile)) {
				inputStore.serialize(stream);
			}
			ISerializableModelStore resultStore = new MultiFormatStore(new InMemSpdxStore(), MultiFormatStore.Format.JSON);
			try (InputStream inStream = new FileInputStream(serFile)) {
				resultStore.deSerialize(inStream, false);
				List<String> restoredDocUris = getDocUris(resultStore);
				assertEquals(1, restoredDocUris.size());
				assertEquals(documentUri, restoredDocUris.get(0));
			}
			SpdxDocument resultDoc = SpdxModelFactoryCompatV2.createSpdxDocumentV2(resultStore, documentUri, new ModelCopyManager());
			verify = resultDoc.verify();
			assertEquals(0, verify.size());
			// validate schema file
			JsonNode spdxJsonSchema = JsonLoader.fromFile(new File(JSON_SCHEMA_V2_3));
			final JsonSchema schema = JsonSchemaFactory.byDefault().getJsonSchema(spdxJsonSchema);
			JsonNode spdxDocJson = JsonLoader.fromFile(serFile);
			ProcessingReport report = schema.validateUnchecked(spdxDocJson, true);
			assertTrue(report.isSuccess());
		} finally {
			if (serFile.exists()) {
				serFile.delete();
			}
			tempDirPath.toFile().delete();
		}
	}

	/**
	 * @param resultStore
	 * @return
	 * @throws InvalidSPDXAnalysisException 
	 */
	private List<String> getDocUris(ISerializableModelStore resultStore) throws InvalidSPDXAnalysisException {
		return resultStore.getAllItems(null, SpdxConstantsCompatV2.CLASS_SPDX_DOCUMENT)
			.map(tv -> tv.getObjectUri().substring(0, tv.getObjectUri().indexOf('#')))
			.collect(Collectors.toList());
	}

	public void testSortOrder() throws InvalidSPDXAnalysisException, IOException, SpdxCompareException, ProcessingException {
		File jsonFile = new File(UNSORTED_JSON);
		MultiFormatStore inputStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputStore.deSerialize(input, false);
		}
		Path tempDirPath = Files.createTempDirectory("mfsTestunsorted");
		File serFile = tempDirPath.resolve("testspdx.json").toFile();
		assertTrue(serFile.createNewFile());
		try {
			try (OutputStream stream = new FileOutputStream(serFile)) {
				inputStore.serialize(stream);
			}
			ISerializableModelStore resultStore = new MultiFormatStore(new InMemSpdxStore(), MultiFormatStore.Format.JSON);
			try (InputStream inStream = new FileInputStream(serFile)) {
				JsonNode root = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).readTree(inStream);
				String previousId = "SPDXRef-Package-0";
				for (JsonNode pkgNode : root.get("packages")) {
					String spdxId = pkgNode.get("SPDXID").asText();
					assertTrue(previousId.compareTo(spdxId) < 0);
					previousId = spdxId;
				}
				previousId = "SPDXRef-File-0";
				for (JsonNode pkgNode : root.get("files")) {
					String spdxId = pkgNode.get("SPDXID").asText();
					assertTrue(previousId.compareTo(spdxId) < 0);
					previousId = spdxId;
				}
			}
		} finally {
			if (serFile.exists()) {
				serFile.delete();
			}
			tempDirPath.toFile().delete();
		}
	}

	public void testRegressionSort() throws IOException {
		File jsonFile = new File(UNSORTED_RELATIONSHIPS);
		ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		JsonNode relationships = null;
		try (FileInputStream stream = new FileInputStream(jsonFile)) {
			relationships  = mapper.readTree(stream);
		}
		assertTrue(relationships.isArray());
		ArrayNode relArray = (ArrayNode)relationships;
		for (int i = 0; i < relArray.size(); i++) {
			for (int j = i; j < relArray.size(); j++) {
				JsonNode rel1 = relArray.get(i);
				JsonNode rel2 = relArray.get(j);
				int result = JacksonSerializer.NODE_COMPARATOR.compare(rel1, rel2);
				int revers = JacksonSerializer.NODE_COMPARATOR.compare(rel2, rel1);
				if (relArray.get(i).equals(relArray.get(j))) {
					assertEquals(0, result);
				}
				if (relArray.get(j).equals(relArray.get(i))) {
					assertEquals(0, result);
				}
				if (result < 0) {
					assertTrue(revers > 0);
				} else if (result > 0) {
					assertTrue(revers < 0);
				} else {
					assertEquals(0, revers);
				}
			}
		}
		JacksonSerializer.sortArrayNode(relArray);
	}

}

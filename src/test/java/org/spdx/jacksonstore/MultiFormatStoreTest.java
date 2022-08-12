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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.spdx.jacksonstore.MultiFormatStore.Format;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.model.Checksum;
import org.spdx.library.model.Relationship;
import org.spdx.library.model.SpdxDocument;
import org.spdx.library.model.SpdxFile;
import org.spdx.library.model.SpdxPackage;
import org.spdx.library.model.enumerations.ChecksumAlgorithm;
import org.spdx.library.model.enumerations.Purpose;
import org.spdx.library.model.enumerations.RelationshipType;
import org.spdx.library.model.license.AnyLicenseInfo;
import org.spdx.library.model.license.SpdxNoAssertionLicense;
import org.spdx.storage.simple.InMemSpdxStore;
import org.spdx.utility.compare.SpdxCompareException;
import org.spdx.utility.compare.SpdxComparer;

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
	/* (non-Javadoc)
	 * @see junit.framework.TestCase#setUp()
	 */
	protected void setUp() throws Exception {
		super.setUp();
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
		try (InputStream input = new FileInputStream(jsonFile)) {
			inputStore.deSerialize(input, false);
		}
		String documentUri = inputStore.getDocumentUris().get(0);
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
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
		inputStore.serialize(documentUri, outputStream);
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
		String documentUri = inputStore.getDocumentUris().get(0);
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
		String documentUri = inputStore.getDocumentUris().get(0);
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		
		// Deserialize
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		inputStore.setFormat(Format.YAML);
		inputStore.serialize(documentUri, outputStream);
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
		String documentUri = inputStore.getDocumentUris().get(0);
		SpdxDocument inputDocument = new SpdxDocument(inputStore, documentUri, null, false);
		List<String> verify = inputDocument.verify();
		assertEquals(0, verify.size());
		
		// Deserialize
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		inputStore.setFormat(Format.XML);
		inputStore.serialize(documentUri, outputStream);
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
		String documentUri = inputStore.getDocumentUris().get(0);
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
		String documentUri = inputStore.getDocumentUris().get(0);
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

}

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
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.spdx.jacksonstore.MultiFormatStore;
import org.spdx.jacksonstore.MultiFormatStore.Format;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.model.SpdxDocument;
import org.spdx.utility.compare.SpdxCompareException;
import org.spdx.utility.compare.SpdxComparer;

import junit.framework.TestCase;

/**
 * @author Gary O'Neall
 *
 */
public class MultiFormatStoreTest extends TestCase {
	
	static final String JSON_FILE_PATH = "testResources" + File.separator + "SPDXJSONExample-v2.0.json";

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
		MultiFormatStore inputStore = new MultiFormatStore(Format.JSON_PRETTY);
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
		MultiFormatStore outputStore = new MultiFormatStore(Format.JSON_PRETTY);
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
	
	public void testDeSerializeSerializeYaml() throws InvalidSPDXAnalysisException, IOException, SpdxCompareException {
		File jsonFile = new File(JSON_FILE_PATH);
		MultiFormatStore inputStore = new MultiFormatStore(Format.JSON_PRETTY);
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
		MultiFormatStore outputStore = new MultiFormatStore(Format.YAML);
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
		MultiFormatStore inputStore = new MultiFormatStore(Format.JSON_PRETTY);
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
		MultiFormatStore outputStore = new MultiFormatStore(Format.XML);
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

}

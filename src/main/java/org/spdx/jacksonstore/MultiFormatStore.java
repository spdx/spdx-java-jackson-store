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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.json.JSONObject;
import org.json.XML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.core.CoreModelObject;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.core.TypedValue;
import org.spdx.library.ModelCopyManager;
import org.spdx.library.SpdxModelFactory;
import org.spdx.library.model.v2.SpdxConstantsCompatV2;
import org.spdx.library.model.v2.SpdxDocument;
import org.spdx.storage.IModelStore;
import org.spdx.storage.ISerializableModelStore;
import org.spdx.storage.simple.ExtendedSpdxStore;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Model store that supports multiple serialization formats (JSON, XML, YAML)
 * 
 * Note that the serialization/deserlization methods are synchronized to prevent the format or verbose changing while serilizing
 * 
 * @author Gary O'Neall
 *
 */
public class MultiFormatStore extends ExtendedSpdxStore implements ISerializableModelStore {
	
	static final Logger logger = LoggerFactory.getLogger(MultiFormatStore.class);
	
	public enum Verbose {
		COMPACT,		// SPDX identifiers are used for any SPDX element references, license expressions as text
		STANDARD,		// Expand referenced SPDX element, license expressions as text
		FULL			// Expand all licenses to full objects and expand all SPDX elements
	};
	
	public enum Format {
		JSON,
		JSON_PRETTY,	// pretty printed JSON format
		XML,
		YAML
	}
	
	private Format format;
	private Verbose verbose;
	static final ObjectMapper JSON_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	static final ObjectMapper XML_MAPPER = new XmlMapper().configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true).enable(SerializationFeature.INDENT_OUTPUT);
	static final YAMLFactory yamlFactory = new YAMLFactory();
	static final ObjectMapper YAML_MAPPER = new ObjectMapper(yamlFactory);
	static final XmlFactory xmlFactory = new XmlFactory();
	
	private ObjectMapper outputMapper;
	private ObjectMapper inputMapper;
	
	/**
	 * @param baseStore modelStore to store the results of the desearialization
	 * @param format Format - XML, JSON or YAML
	 * @param verbose How verbose to make the document
	 */
	public MultiFormatStore(IModelStore baseStore, Format format, Verbose verbose) {
		super(baseStore);
		Objects.requireNonNull(format);
		Objects.requireNonNull(verbose);
		this.format = format;
		this.verbose = verbose;
		setMapper();
	}
	
	/**
	 * Set the mapper based on the format
	 */
	private void setMapper() {
		switch (format) {
		case XML: outputMapper = XML_MAPPER; inputMapper = JSON_MAPPER; break;
		case YAML: outputMapper = YAML_MAPPER; inputMapper = YAML_MAPPER; break;
		case JSON: 
		case JSON_PRETTY: 
		default: outputMapper = JSON_MAPPER; inputMapper = JSON_MAPPER;
		}
	}
	
	/**
	 * Default compact version of MultiFormatStore
	 * @param baseStore modelStore to store the results of the desearialization
	 * @param format Format - XML, JSON or YAML
	 */
	public MultiFormatStore(IModelStore baseStore, Format format) {
		this(baseStore, format, Verbose.COMPACT);
	}
	
	

	/**
	 * @return the format
	 */
	public synchronized Format getFormat() {
		return format;
	}



	/**
	 * @param format the format to set
	 */
	public synchronized void setFormat(Format format) {
		Objects.requireNonNull(format);
		this.format = format;
		setMapper();
	}



	/**
	 * @return the verbose
	 */
	public synchronized Verbose getVerbose() {
		return verbose;
	}



	/**
	 * @param verbose the verbose to set
	 */
	public synchronized void setVerbose(Verbose verbose) {
		Objects.requireNonNull(verbose);
		this.verbose = verbose;
		setMapper();
	}

	@Override
	public synchronized void serialize(OutputStream stream) throws InvalidSPDXAnalysisException, IOException {
		serialize(stream, null);
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#serialize(java.lang.String, java.io.OutputStream)
	 */
	@Override
	public synchronized void serialize(OutputStream stream, @Nullable CoreModelObject modelObject) throws InvalidSPDXAnalysisException, IOException {
		JacksonSerializer serializer = new JacksonSerializer(outputMapper, format, verbose, this);
		JsonNode output;
		if (Objects.nonNull(modelObject)) {
			output = serializer.docToJsonNode(modelObject.getObjectUri().substring(0, modelObject.getObjectUri().indexOf('#')));
		} else {
			List<String> allDocuments = getAllItems(null, SpdxConstantsCompatV2.CLASS_SPDX_DOCUMENT)
					.map(tv -> tv.getObjectUri().substring(0, tv.getObjectUri().indexOf('#')))
					.collect(Collectors.toList());
			if (allDocuments.isEmpty()) {
				logger.warn("No SPDX Spec Version 2 Documents were found to serialize.  Note: For SPDX Spec version 3, the spdx-v3jsonld-store should be used");
			}
			output = allDocuments.size() == 1 ? serializer.docToJsonNode(allDocuments.get(0)) :
					serializer.docsToJsonNode(allDocuments);
		}
		JsonGenerator jgen = null;
		try {
    		switch (format) {
    			case YAML: {
    				jgen = yamlFactory.createGenerator(stream); 
    				break;
    			}
    			case XML: {
    				jgen = outputMapper.getFactory().createGenerator(stream).useDefaultPrettyPrinter(); 
    				break;
    			}
    			case JSON: {
    				jgen = outputMapper.getFactory().createGenerator(stream);
    				break;
    			}
    			case JSON_PRETTY:
    			default:  {
    				jgen = outputMapper.getFactory().createGenerator(stream).useDefaultPrettyPrinter(); 
    				break;
    			}
    		}
			outputMapper.writeTree(jgen, output);
		} finally {
		    if (Objects.nonNull(jgen)) {
		        jgen.close();
		    }
		}
	}

	/**
	 * @param propertyName
	 * @return property name used for an array or collection of these values
	 */
	public static String propertyNameToCollectionPropertyName(String propertyName) {
		if (propertyName.endsWith("y")) {
			return propertyName.substring(0, propertyName.length()-1) + "ies";
		} else if (SpdxConstantsCompatV2.PROP_PACKAGE_LICENSE_INFO_FROM_FILES.getName().equals(propertyName)) {
			return propertyName;
		} else {
			return propertyName + "s";
		}
	}
	
	public static String collectionPropertyNameToPropertyName(String collectionPropertyName) {
		if (collectionPropertyName.endsWith("ies")) {
			return collectionPropertyName.substring(0, collectionPropertyName.length()-3) + "y";
		} else if (SpdxConstantsCompatV2.PROP_PACKAGE_LICENSE_INFO_FROM_FILES.getName().equals(collectionPropertyName)) {
			return collectionPropertyName;
		} else {
			return collectionPropertyName.substring(0, collectionPropertyName.length()-1);
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#deSerialize(java.io.InputStream, boolean)
	 */
	@Override
	public synchronized SpdxDocument deSerialize(InputStream stream, boolean overwrite) throws InvalidSPDXAnalysisException, IOException {
		Objects.requireNonNull(stream, "Input stream must not be null");
		if (this.verbose != Verbose.COMPACT) {
			throw new InvalidSPDXAnalysisException("Only COMPACT verbose option is supported for deserialization");
		}
		JsonNode root;
		if (Format.XML.equals(format)) {
			// Jackson XML mapper does not support deserializing collections or arrays.  Use Json-In-Java to convert to JSON
			JSONObject jo = XML.toJSONObject(new InputStreamReader(stream, "UTF-8"));
			root = inputMapper.readTree(jo.toString()).get("Document");
		} else {
			root  = inputMapper.readTree(stream);
		}
		if (Objects.isNull(root)) {
			throw new InvalidSPDXAnalysisException("Missing SPDX Document");
		}
		List<String> documentNamespaces = new ArrayList<>();
		if (root instanceof ArrayNode) {
			for (JsonNode docNode:(ArrayNode)root) {
				documentNamespaces.add(getNamespaceFromDoc(docNode));
			}
		} else {
			documentNamespaces.add(getNamespaceFromDoc(root));
		}
		
		Set<String> existingDocNamespaces = this.getDocumentUris();
		boolean existing = false;
		for (String docNamespace:documentNamespaces) {
			if (existingDocNamespaces.contains(docNamespace)) {
				existing = true;
				break;
			}
		}
		if (existing) {
			IModelStoreLock lock = this.enterCriticalSection(false);
			try {
				if (!overwrite) {
					throw new InvalidSPDXAnalysisException("Document namespace(s) already exists.");
				}
				for (String docNamespace:documentNamespaces) {
					if (existingDocNamespaces.contains(docNamespace)) {
						this.clear(docNamespace);
					}
				}
			} finally {
				this.leaveCriticalSection(lock);
			}
		}
		JacksonDeSerializer deSerializer = new JacksonDeSerializer(this, format);
		String docNamespace;
		if (root instanceof ArrayNode) {
			for (JsonNode doc:(ArrayNode)root) {
				deSerializer.storeDocument(getNamespaceFromDoc(doc), doc);
			}
			docNamespace = getNamespaceFromDoc((ArrayNode)root.get(0));
		} else {
			deSerializer.storeDocument(getNamespaceFromDoc(root), root);
			docNamespace = getNamespaceFromDoc(root);
		}
		return (SpdxDocument)SpdxModelFactory.inflateModelObject(this, docNamespace + "#" + SpdxConstantsCompatV2.SPDX_DOCUMENT_ID,  
				SpdxConstantsCompatV2.CLASS_SPDX_DOCUMENT, new ModelCopyManager(), 
				SpdxConstantsCompatV2.SPEC_TWO_POINT_THREE_VERSION, false, docNamespace);
	}

	/**
	 * Get the document namespace from the JSON node representing the SPDX document
	 * @param docNode root of the SPDX document
	 * @throws InvalidSPDXAnalysisException on missing document namespace
	 */
	private String getNamespaceFromDoc(JsonNode docNode) throws InvalidSPDXAnalysisException {
		JsonNode namespaceNode = docNode.get(SpdxConstantsCompatV2.PROP_DOCUMENT_NAMESPACE.getName());
		if (Objects.isNull(namespaceNode)) {
			throw new InvalidSPDXAnalysisException("Missing document namespace");
		}
		String documentNamespace = namespaceNode.asText();
		if (Objects.isNull(documentNamespace) || documentNamespace.isEmpty()) {
			throw new InvalidSPDXAnalysisException("Empty document namespace");
		}
		return documentNamespace;
	}

	/**
	 * @param documentNamespace
	 * @throws InvalidSPDXAnalysisException 
	 */
	public void clear(String documentNamespace) throws InvalidSPDXAnalysisException {
		List<TypedValue> valuesToDelete = this.getAllItems(documentNamespace, null).collect(Collectors.toList());
		for (TypedValue valueToDelete:valuesToDelete) {
			this.delete(valueToDelete.getObjectUri());
		}
	}

	/**
	 * @return list of SPDX V2 document URI's in this model store 
	 * @throws InvalidSPDXAnalysisException 
	 */
	public Set<String> getDocumentUris() throws InvalidSPDXAnalysisException {
		Set<String> retval = new HashSet<>();
		this.getAllItems(null, null).forEach(tv -> {
			if (tv.getObjectUri().contains("#")) {
				retval.add(tv.getObjectUri().substring(0, tv.getObjectUri().indexOf('#')));
			}
		});
		return retval;
	}
}
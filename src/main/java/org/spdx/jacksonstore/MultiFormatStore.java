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
import java.util.Objects;

import org.json.JSONObject;
import org.json.XML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.storage.IModelStore;
import org.spdx.storage.ISerializableModelStore;
import org.spdx.storage.simple.ExtendedSpdxStore;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
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



	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#serialize(java.lang.String, java.io.OutputStream)
	 */
	@Override
	public synchronized void serialize(String documentUri, OutputStream stream) throws InvalidSPDXAnalysisException, IOException {
		JacksonSerializer serializer = new JacksonSerializer(outputMapper, format, verbose, this);
		ObjectNode output = serializer.docToJsonNode(documentUri);
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
		} else if (SpdxConstants.PROP_PACKAGE_LICENSE_INFO_FROM_FILES.equals(propertyName)) {
			return propertyName;
		} else {
			return propertyName + "s";
		}
	}
	
	public static String collectionPropertyNameToPropertyName(String collectionPropertyName) {
		if (collectionPropertyName.endsWith("ies")) {
			return collectionPropertyName.substring(0, collectionPropertyName.length()-3) + "y";
		} else if (SpdxConstants.PROP_PACKAGE_LICENSE_INFO_FROM_FILES.equals(collectionPropertyName)) {
			return collectionPropertyName;
		} else {
			return collectionPropertyName.substring(0, collectionPropertyName.length()-1);
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#deSerialize(java.io.InputStream, boolean)
	 */
	@Override
	public synchronized String deSerialize(InputStream stream, boolean overwrite) throws InvalidSPDXAnalysisException, IOException {
		Objects.requireNonNull(stream, "Input stream must not be null");
		if (this.verbose != Verbose.COMPACT) {
			throw new InvalidSPDXAnalysisException("Only COMPACT verbose option is supported for deserialization");
		}
		JsonNode doc;
		if (Format.XML.equals(format)) {
			// Jackson XML mapper does not support deserializing collections or arrays.  Use Json-In-Java to convert to JSON
			JSONObject jo = XML.toJSONObject(new InputStreamReader(stream, "UTF-8"));
			doc = inputMapper.readTree(jo.toString()).get("Document");
		} else {
			doc  = inputMapper.readTree(stream);
		}
		if (Objects.isNull(doc)) {
			throw new InvalidSPDXAnalysisException("Missing SPDX Document");
		}
		JsonNode namespaceNode = doc.get(SpdxConstants.PROP_DOCUMENT_NAMESPACE);
		if (Objects.isNull(namespaceNode)) {
			throw new InvalidSPDXAnalysisException("Missing document namespace");
		}
		String documentNamespace = namespaceNode.asText();
		if (Objects.isNull(documentNamespace) || documentNamespace.isEmpty()) {
			throw new InvalidSPDXAnalysisException("Empty document namespace");
		}
		if (this.getDocumentUris().contains(documentNamespace)) {
			IModelStoreLock lock = this.enterCriticalSection(documentNamespace, false);
			try {
				if (!overwrite) {
					throw new InvalidSPDXAnalysisException("Document namespace "+documentNamespace+" already exists.");
				}
				this.clear(documentNamespace);
			} finally {
				this.leaveCriticalSection(lock);
			}
		}
		JacksonDeSerializer deSerializer = new JacksonDeSerializer(this, format);
		deSerializer.storeDocument(documentNamespace, doc);	
		return documentNamespace;

	}
}
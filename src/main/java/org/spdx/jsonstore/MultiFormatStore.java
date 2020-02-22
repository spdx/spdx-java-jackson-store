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
package org.spdx.jsonstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.library.model.ExternalDocumentRef;
import org.spdx.library.model.ExternalSpdxElement;
import org.spdx.library.model.IndividualUriValue;
import org.spdx.library.model.ReferenceType;
import org.spdx.library.model.SimpleUriValue;
import org.spdx.library.model.SpdxDocument;
import org.spdx.library.model.SpdxElement;
import org.spdx.library.model.SpdxInvalidTypeException;
import org.spdx.library.model.SpdxModelFactory;
import org.spdx.library.model.TypedValue;
import org.spdx.library.model.enumerations.RelationshipType;
import org.spdx.library.model.enumerations.SpdxEnumFactory;
import org.spdx.library.model.license.AnyLicenseInfo;
import org.spdx.library.model.license.LicenseInfoFactory;
import org.spdx.storage.IModelStore;
import org.spdx.storage.ISerializableModelStore;
import org.spdx.storage.simple.InMemSpdxStore;
import org.spdx.storage.simple.StoredTypedItem;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
public class MultiFormatStore extends InMemSpdxStore implements ISerializableModelStore {
	
	/**
	 * Class to for the name of the XML element to Document
	 *
	 */
	class Document extends ObjectNode {

		public Document(JsonNodeFactory nc) {
			super(nc);
		}
		
	}
	
	/**
	 * Properties that should not be restored as part of the deserialization
	 */
	static final Set<String> SKIPPED_PROPERTIES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(new String[] {
			SpdxConstants.PROP_DOCUMENT_DESCRIBES, SpdxConstants.PROP_DOCUMENT_PACKAGES, SpdxConstants.PROP_DOCUMENT_FILES,
			SpdxConstants.PROP_DOCUMENT_SNIPPETS, SpdxConstants.SPDX_IDENTIFIER, SpdxConstants.PROP_DOCUMENT_RELATIONSHIPS
	})));
	
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
	static final XmlFactory xmlFactory = new XmlFactory();
	
	private ObjectMapper mapper;
	
	/**
	 * @param format Format - XML, JSON or YAML
	 * @param verbose How verbose to make the document
	 */
	public MultiFormatStore(Format format, Verbose verbose) {
		super();
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
		case XML: mapper = XML_MAPPER; break;
		case JSON: 
		case JSON_PRETTY: 
		default: mapper = JSON_MAPPER;
		}
	}
	
	/**
	 * Default compact version of MultiFormatStore
	 * @param format Format - XML, JSON or YAML
	 */
	public MultiFormatStore(Format format) {
		this(format, Verbose.COMPACT);
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
		IModelStoreLock lock = this.enterCriticalSection(documentUri, false);	//TODO: True value causes deadlock due to false value in ExternalDocumentRef line 58
		try {
			TypedValue document = new TypedValue(SpdxConstants.SPDX_DOCUMENT_ID, SpdxConstants.CLASS_SPDX_DOCUMENT);
			ArrayNode relationships = mapper.createArrayNode();
			ObjectNode doc = typedValueToObjectNode(documentUri, document, relationships);
			doc.put(SpdxConstants.PROP_DOCUMENT_NAMESPACE, documentUri);
			ArrayNode documentDescribes = getDocumentDescribes(relationships);
			doc.set(SpdxConstants.PROP_DOCUMENT_DESCRIBES, documentDescribes);
			ArrayNode packages = getDocElements(documentUri, SpdxConstants.CLASS_SPDX_PACKAGE, relationships);
			if (packages.size() > 0) {
				doc.set(SpdxConstants.PROP_DOCUMENT_PACKAGES, packages);
			}
			ArrayNode files = getDocElements(documentUri, SpdxConstants.CLASS_SPDX_FILE, relationships);
			if (files.size() > 0) {
				doc.set(SpdxConstants.PROP_DOCUMENT_FILES, files);
			}
			ArrayNode snippets = getDocElements(documentUri, SpdxConstants.CLASS_SPDX_SNIPPET, relationships);
			if (snippets.size() > 0) {
				doc.set(SpdxConstants.PROP_DOCUMENT_SNIPPETS, snippets);
			}
			//TODO: Remove duplicate relationships
			doc.set(SpdxConstants.PROP_DOCUMENT_RELATIONSHIPS, relationships);
			ObjectNode output;
			JsonGenerator jgen;
			switch (format) {
				case YAML: {
					jgen = yamlFactory.createGenerator(stream); 
					output = mapper.createObjectNode();
					output.set("Document", doc);
					break;
				}
				case XML: {
					jgen = mapper.getFactory().createGenerator(stream).useDefaultPrettyPrinter(); 
					output = new Document(JsonNodeFactory.instance);
					output.setAll(doc);
					break;
				}
				case JSON: {
					jgen = mapper.getFactory().createGenerator(stream);
					output = mapper.createObjectNode();
					output.set("Document", doc);
					break;
				}
				case JSON_PRETTY:
				default:  {
					jgen = mapper.getFactory().createGenerator(stream).useDefaultPrettyPrinter(); 
					output = mapper.createObjectNode();
					output.set("Document", doc);
					break;
				}
			}
			try {
				mapper.writeTree(jgen, output);
			} finally {
				jgen.close();
			}
		} finally {
			this.leaveCriticalSection(lock);
		}
	}

	/**
	 * @param documentUri Document namespace or Uri
	 * @param type type of document element to get (Package, File, or Snippet)
	 * @return JsonArray of document elements matching the type
	 * @throws InvalidSPDXAnalysisException
	 */
	private ArrayNode getDocElements(String documentUri, String type, ArrayNode relationships) throws InvalidSPDXAnalysisException {
		ArrayNode retval = mapper.createArrayNode();
		this.getAllItems(documentUri, type).forEach(tv -> {
			try {
				retval.add(typedValueToObjectNode(documentUri, tv, relationships));
			} catch (InvalidSPDXAnalysisException e) {
				throw new RuntimeException(e);
			}
		});
		return retval;
	}

	/**
	 * @param relationships all relationships in the document
	 * @return all related element IDs for DOCUMENT_DESCRIBES relationships from the Spdx Document
	 */
	private ArrayNode getDocumentDescribes(ArrayNode relationships) {
		ArrayNode documentDescribes = mapper.createArrayNode();
		for (JsonNode relationship:relationships) {
			if (relationship.isObject()) {
				JsonNode typeNode = relationship.get(SpdxConstants.PROP_RELATIONSHIP_TYPE);
				if (typeNode.isTextual() &&
						"DESCRIBES".equals(typeNode.asText())) {
					JsonNode elementId = relationship.get(SpdxConstants.PROP_SPDX_ELEMENTID);
					if (elementId.isTextual() &&
							SpdxConstants.SPDX_DOCUMENT_ID.equals(elementId.asText())) {
						JsonNode relatedElement = relationship.get(SpdxConstants.PROP_RELATED_SPDX_ELEMENT);
						if (relatedElement.isTextual()) {
							documentDescribes.add(relatedElement.asText());
						}
					}
				}
			}
		}
		return documentDescribes;
	}
	
	/**
	 * Add a stored object value to an array node converting the value
	 * @param node array node to add the element
	 * @param documentUri Document namespace or Uri
	 * @param value Value to convert to a JSON serializable form
	 * @param relationships ArrayNode of relationships to add any found relationships
	 * @throws InvalidSPDXAnalysisException
	 */
	private void addValueToArrayNode(ArrayNode node, String documentUri, Object value, ArrayNode relationships) throws InvalidSPDXAnalysisException {
		Object nodeValue = toSerializable(documentUri, value, relationships);
		if (nodeValue instanceof JsonNode) {
			node.add((JsonNode)nodeValue);
		} else if (nodeValue instanceof String) {
			node.add((String)nodeValue);
		} else if (nodeValue instanceof Integer) {
			node.add((Integer)nodeValue);
		} else if (nodeValue instanceof Boolean) {
			node.add((Boolean)nodeValue);
		}else {
			throw new SpdxInvalidTypeException("Can not serialize the JSON type for "+nodeValue.getClass().toString());
		}
	}
	
	/**
	 * Add a stored value to a field in a nodeObject converting the value
	 * @param node nodeObject to store the value
	 * @param field field associated with the value
	 * @param documentUri Document namespace or Uri
	 * @param value Value to convert to a JSON serializable form
	 * @param relationships ArrayNode of relationships to add any found relationships
	 * @throws InvalidSPDXAnalysisException
	 */
	private void setValueField(ObjectNode node, String field, String documentUri, Object value, ArrayNode relationships) throws InvalidSPDXAnalysisException {
		Object nodeValue = toSerializable(documentUri, value, relationships);
		if (nodeValue instanceof JsonNode) {
			node.set(field, (JsonNode)nodeValue);
		} else if (nodeValue instanceof String) {
			node.put(field, (String)nodeValue);
		} else if (nodeValue instanceof Integer) {
			node.put(field, (Integer)nodeValue);
		} else if (nodeValue instanceof Boolean) {
			node.put(field, (Boolean)nodeValue);
		}else {
			throw new SpdxInvalidTypeException("Can not serialize the JSON type for "+nodeValue.getClass().toString());
		}
	}

	/**
	 * Converts a stored value object into a serializable form
	 * @param documentUri Document namespace or Uri
	 * @param value Value to convert to a JSON serializable form
	 * @param relationships ArrayNode of relationships to add any found relationships
	 * @throws InvalidSPDXAnalysisException 
	 */
	private Object toSerializable(String documentUri, Object value, ArrayNode relationships) throws InvalidSPDXAnalysisException {
		if (value instanceof IndividualUriValue) {
			return individualUriToString(documentUri, ((IndividualUriValue)value).getIndividualURI());
		} else if (value instanceof TypedValue) {
			TypedValue tvStoredValue = (TypedValue)value;
			Class<?> clazz = SpdxModelFactory.SPDX_TYPE_TO_CLASS.get(tvStoredValue.getType());
			if (AnyLicenseInfo.class.isAssignableFrom(clazz) && 
					(Verbose.STANDARD.equals(verbose) || Verbose.COMPACT.equals(verbose))) {
				AnyLicenseInfo ali = (AnyLicenseInfo)SpdxModelFactory.createModelObject(this, documentUri, 
						tvStoredValue.getId(), tvStoredValue.getType(), null);
				return ali.toString();
			} else if (SpdxElement.class.isAssignableFrom(clazz) &&
					Verbose.COMPACT.equals(verbose) &&
					!IModelStore.IdType.Anonymous.equals(getIdType(tvStoredValue.getId()))) {
				return tvStoredValue.getId();
			} else {
				return typedValueToObjectNode(documentUri, (TypedValue)value, relationships);
			}
		} else {
			return value;
		}
	}

	/**
	 * Convert a typed value into an ObjectNode adding all stored properties
	 * @param documentUri Document namespace or Uri
	 * @param storedItem stored value to convert to a JSON serializable form
	 * @param relationships ArrayNode of relationships to add any found relationships
	 * @return ObjectNode with all fields added from the stored typedValue
	 * @throws InvalidSPDXAnalysisException 
	 */
	private ObjectNode typedValueToObjectNode(String documentUri, TypedValue storedItem, ArrayNode relationships) throws InvalidSPDXAnalysisException {
		ObjectNode retval = mapper.createObjectNode();
		List<String> docPropNames = new ArrayList<String>(this.getPropertyValueNames(documentUri, storedItem.getId()));
		docPropNames.sort(new PropertyComparator(storedItem.getType()));
		//TODO - do we sort for all types or just for the document level?
		Class<?> clazz = SpdxModelFactory.SPDX_TYPE_TO_CLASS.get(storedItem.getType());
		if (SpdxElement.class.isAssignableFrom(clazz) && 
				IdType.SpdxId.equals(getIdType(storedItem.getId()))) {
			retval.put(SpdxConstants.SPDX_IDENTIFIER, storedItem.getId());
		}
		if (ExternalDocumentRef.class.isAssignableFrom(clazz)) {
			retval.put(SpdxConstants.EXTERNAL_DOCUMENT_REF_IDENTIFIER, storedItem.getId());
		}
		for (String propertyName:docPropNames) {
			if (SpdxConstants.PROP_RELATIONSHIP.equals(propertyName)) {
				for (ObjectNode relationship:toJsonRelationships(documentUri, storedItem.getId(), getValueList(documentUri, storedItem.getId(), SpdxConstants.PROP_RELATIONSHIP))) {
					relationships.add(relationship);
				}
			} else if (SpdxConstants.PROP_SPDX_EXTRACTED_LICENSES.equals(propertyName)) {
				if (Format.XML.equals(format)) {
					retval.set(propertyName, toExtractedLicensesArrayNode(documentUri, storedItem.getId(), propertyName, relationships));
				} else {
					retval.set(propertyNameToCollectionPropertyName(propertyName), 
							toExtractedLicensesArrayNode(documentUri, storedItem.getId(), propertyName, relationships));
				}
			} else if (this.isCollectionProperty(documentUri, storedItem.getId(), propertyName)) {
				ArrayNode valuesArray = toArrayNode(documentUri, this.getValueList(documentUri, storedItem.getId(), propertyName), 
						relationships);
				if (Format.XML.equals(format)) {
					retval.set(propertyName, valuesArray);
				} else {
					retval.set(propertyNameToCollectionPropertyName(propertyName), 
							valuesArray);
				}
			} else {
				Optional<Object> value = this.getValue(documentUri, storedItem.getId(), propertyName);
				if (value.isPresent()) {
					setValueField(retval, propertyName, documentUri, value.get(), relationships);
				}
			}
		}
		return retval;
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
			return collectionPropertyName.substring(0, collectionPropertyName.length()-3);
		} else if (SpdxConstants.PROP_PACKAGE_LICENSE_INFO_FROM_FILES.equals(collectionPropertyName)) {
			return collectionPropertyName;
		} else {
			return collectionPropertyName.substring(0, collectionPropertyName.length()-1);
		}
	}

	/**
	 * Convert a list of values to an ArrayNode for serialization
	 * @param documentUri Document namespace or Uri
	 * @param valueList list of values to convert
	 * @param relationships running total of any relationships found - any relationships are added
	 * @return
	 * @throws InvalidSPDXAnalysisException 
	 */
	private ArrayNode toArrayNode(String documentUri, List<Object> valueList, ArrayNode relationships) throws InvalidSPDXAnalysisException {
		ArrayNode retval = mapper.createArrayNode();
		for (Object value:valueList) {
			addValueToArrayNode(retval, documentUri, value, relationships);
		}
		return retval;
	}

	/**
	 * This method is used for the extracted licenses otherwise just the ID of the license would be written
	 * @param documentUri  Document namespace or Uri
	 * @param id document ID containing the extracted licenses
	 * @param propertyName property name for the extracted licenses
	 * @param relationships list of relationships - just so we can pass it to toJsonObject
	 * @return an ArrayNode of extracted license elements
	 * @throws InvalidSPDXAnalysisException
	 */
	private ArrayNode toExtractedLicensesArrayNode(String documentUri, String id, String propertyName,
			ArrayNode relationships) throws InvalidSPDXAnalysisException {
		ArrayNode retval = mapper.createArrayNode();
		List<Object> extractedLicenses = this.getValueList(documentUri, id, propertyName);
		for (Object extractedLicense:extractedLicenses) {
			if (!(extractedLicense instanceof TypedValue) || 
					(!SpdxConstants.CLASS_SPDX_EXTRACTED_LICENSING_INFO.equals(((TypedValue)extractedLicense).getType()))) {
				throw new SpdxInvalidTypeException("Extracted License Infos not of type "+SpdxConstants.CLASS_SPDX_EXTRACTED_LICENSING_INFO);
			}
			retval.add(typedValueToObjectNode(documentUri, (TypedValue)extractedLicense, relationships));
		}
		return retval;
	}

	/**
	 * @param documentUri Document namespace or Uri
	 * @param id element ID for the element containing the relationship
	 * @param valueList List of all relationships
	 * @return collection of JsonObjects representing the relationships
	 * @throws InvalidSPDXAnalysisException
	 */
	private Collection<? extends ObjectNode> toJsonRelationships(String documentUri, String id, 
			List<Object> valueList) throws InvalidSPDXAnalysisException {
		ArrayList<ObjectNode> retval = new ArrayList<>();
		for (Object value:valueList) {
			if (!(value instanceof TypedValue)) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+value.getClass().toString());
			}
			TypedValue tvValue = (TypedValue)value;
			if (!SpdxConstants.CLASS_RELATIONSHIP.equals(tvValue.getType())) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+tvValue.getType());
			}
			ObjectNode relationship = mapper.createObjectNode();
			relationship.put(SpdxConstants.PROP_SPDX_ELEMENTID, id);
			Optional<Object> relatedSpdxElement = getValue(documentUri, tvValue.getId(), SpdxConstants.PROP_RELATED_SPDX_ELEMENT); 
			if (!relatedSpdxElement.isPresent()) {
				logger.warn("Missing related SPDX element for a relationship for "+id+".  Skipping the serialization of this relationship.");
				continue;
			}
			if (relatedSpdxElement.get() instanceof TypedValue) {
				String relatedElementId = ((TypedValue)relatedSpdxElement.get()).getId();
				relationship.put(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, relatedElementId);
			} else if (relatedSpdxElement.get() instanceof IndividualUriValue) {
				// external SPDX element
				String externalUri = ((IndividualUriValue)relatedSpdxElement.get()).getIndividualURI();
				if (!SpdxConstants.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(externalUri).matches()) {
					throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement or external SPDX element type.  URI does not match pattern for external element: "+externalUri);
				}
				ExternalSpdxElement externalElement = ExternalSpdxElement.uriToExternalSpdxElement(externalUri, this, documentUri, null);
				relationship.put(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, externalElement.getExternalDocumentId() + ":" + externalElement.getExternalElementId());
			}  else {
				throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement or external SPDX element type.  Found type "+relatedSpdxElement.get().getClass().toString());
			}
			
			Optional<Object> type = getValue(documentUri, tvValue.getId(), SpdxConstants.PROP_RELATIONSHIP_TYPE);
			if (!type.isPresent()) {
				logger.warn("Missing type for a relationship for "+id+".  Skipping the serialization of this relationship.");
				continue;
			}
			if (!(type.get() instanceof IndividualUriValue)) {
				throw new SpdxInvalidTypeException("Expected RelationshipType type for relationshipType property.  Unexpected type "+relatedSpdxElement.get().getClass().toString());
			}
			relationship.put(SpdxConstants.PROP_RELATIONSHIP_TYPE, individualUriToString(documentUri, ((IndividualUriValue)type.get()).getIndividualURI()));
			retval.add(relationship);
		}
		return retval;

	}
	
	/**
	 * Converts a URI to a JSON string value.  The URI may represent an enumeration value or a literal value (like NONE or NOASSERTION).
	 * @param documentUri Document namespace or Uri
	 * @param uri URI value
	 * @return JSON form of the Enum or literal value represented by the URI
	 * @throws InvalidSPDXAnalysisException
	 */
	private String individualUriToString(String documentUri, String uri) throws InvalidSPDXAnalysisException {
		Object enumval = SpdxEnumFactory.uriToEnum.get(uri);
		if (Objects.nonNull(enumval)) {
			return enumval.toString();
		} else if (SpdxConstants.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(uri).matches()) {
			ExternalSpdxElement externalElement = ExternalSpdxElement.uriToExternalSpdxElement(uri, this, documentUri, null);
			return externalElement.getExternalDocumentId() + ":" + externalElement.getExternalElementId();
		} else if (SpdxConstants.URI_VALUE_NONE.equals(uri)) {
			return SpdxConstants.NONE_VALUE;
		} else if (SpdxConstants.URI_VALUE_NOASSERTION.equals(uri)) {
			return SpdxConstants.NOASSERTION_VALUE;
		} else {
			return uri;
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#deSerialize(java.io.InputStream, boolean)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public synchronized String deSerialize(InputStream stream, boolean overwrite) throws InvalidSPDXAnalysisException, IOException {
		Objects.requireNonNull(stream, "Input stream must not be null");
		if (this.verbose != Verbose.COMPACT) {
			throw new InvalidSPDXAnalysisException("Only COMPACT verbose option is supported for deserialization");
		}
		JsonNode root = mapper.readTree(stream);
		JsonNode doc = root.get("Document");
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
		IModelStoreLock lock = this.enterCriticalSection(documentNamespace, false);
		try {
			ConcurrentHashMap<String, StoredTypedItem> idMap = documentValues.get(documentNamespace);
			if (Objects.nonNull(idMap)) {
				if (!overwrite) {
					throw new InvalidSPDXAnalysisException("Document namespace "+documentNamespace+" already exists.");
				}
				idMap.clear();
			} else {
				while (idMap == null) {
					idMap = documentValues.putIfAbsent(documentNamespace, new ConcurrentHashMap<String, StoredTypedItem>());
				}
			}
			Map<String, String> spdxIdProperties = new HashMap<>();	// properties which contain an SPDX id which needs to be replaced
			create(documentNamespace, SpdxConstants.SPDX_DOCUMENT_ID, SpdxConstants.CLASS_SPDX_DOCUMENT);
			restoreObjectPropertyValues(documentNamespace, SpdxConstants.SPDX_DOCUMENT_ID, doc, spdxIdProperties);
			// restore the packages
			Map<String, TypedValue> addedElements = new HashMap<>();
			addedElements.put(SpdxConstants.SPDX_DOCUMENT_ID, new TypedValue(SpdxConstants.SPDX_DOCUMENT_ID, SpdxConstants.CLASS_SPDX_DOCUMENT));
			restoreElements(documentNamespace, SpdxConstants.CLASS_SPDX_PACKAGE, 
					doc.get(SpdxConstants.PROP_DOCUMENT_PACKAGES), addedElements, spdxIdProperties);
			restoreElements(documentNamespace, SpdxConstants.CLASS_SPDX_FILE, 
					doc.get(SpdxConstants.PROP_DOCUMENT_FILES), addedElements, spdxIdProperties);
			restoreElements(documentNamespace, SpdxConstants.CLASS_SPDX_SNIPPET, 
					doc.get(SpdxConstants.PROP_DOCUMENT_SNIPPETS), addedElements, spdxIdProperties);
			restoreRelationships(documentNamespace, doc.get(SpdxConstants.PROP_DOCUMENT_RELATIONSHIPS),
					addedElements);
			// fix up the ID's
			for (Entry<String, String> propertyToFix:spdxIdProperties.entrySet()) {
				Optional<Object> idToReplace = this.getValue(documentNamespace, propertyToFix.getKey(), propertyToFix.getValue());
				if (!idToReplace.isPresent()) {
					throw new InvalidSPDXAnalysisException("Missing SPDX ID for "+propertyToFix.getKey() + " " + propertyToFix.getValue());
				}
				if (idToReplace.get() instanceof Collection) {
					Collection<Object> replacements = new HashSet<>();
					for (Object spdxId:(Collection<Object>)(idToReplace.get())) {
						if (!(spdxId instanceof String)) {
							throw new InvalidSPDXAnalysisException("Can not replace the SPDX ID with value due to invalid type for "+propertyToFix.getKey() + " " + propertyToFix.getValue());
						}
						replacements.add(idToObjectValue(documentNamespace, (String)spdxId, addedElements));

					}
					clearValueCollection(documentNamespace, propertyToFix.getKey(), propertyToFix.getValue());
					for (Object replacement:replacements) {
						addValueToCollection(documentNamespace, propertyToFix.getKey(), propertyToFix.getValue(), replacement);
					}
					
				} else {
					if (!(idToReplace.get() instanceof String)) {
						throw new InvalidSPDXAnalysisException("Can not replace the SPDX ID with value due to invalid type for "+propertyToFix.getKey() + " " + propertyToFix.getValue());
					}
					String spdxId = (String)idToReplace.get();
					setValue(documentNamespace, propertyToFix.getKey(), propertyToFix.getValue(), idToObjectValue(documentNamespace, spdxId, addedElements));
				}
			}
			return documentNamespace;
		} finally {
			this.leaveCriticalSection(lock);
		}
	}

	/**
	 * Convert an ID into the value object to be stored
	 * @param documentNamespace
	 * @param spdxId ID to be replaced by the actual object
	 * @param addedElements SPDX elements added
	 * @return
	 * @throws InvalidSPDXAnalysisException 
	 */
	private Object idToObjectValue(String documentNamespace, String spdxId, Map<String, TypedValue> addedElements) throws InvalidSPDXAnalysisException {
		TypedValue fixedValue = addedElements.get(spdxId);
		if (Objects.isNull(fixedValue)) {
			if (spdxId.startsWith("DocumentRef-")) {
				final IModelStore modelStore = this;
				IndividualUriValue spdxExternalElementRef = new IndividualUriValue() {

					@Override
					public String getIndividualURI() {
						try {
							return ExternalSpdxElement.externalSpdxElementIdToURI(spdxId, modelStore, documentNamespace, null);
						} catch (InvalidSPDXAnalysisException e) {
							throw new RuntimeException(e);
						}
					}
					
				};
				return spdxExternalElementRef;
			} else {
				throw new InvalidSPDXAnalysisException("No SPDX element found for SPDX ID "+spdxId);
			}
		} else {
			return fixedValue;
		}
	}

	/**
	 * Restores SPDX elements of a specific type
	 * @param documentUri
	 * @param type
	 * @param jsonNode
	 * @param addedElements
	 * @param spdxIdProperties Properties which contain an SPDX ID which needs to be replaced
	 * @throws InvalidSPDXAnalysisException
	 */
	private void restoreElements(String documentUri, String type, @Nullable JsonNode jsonNode,
			Map<String, TypedValue> addedElements, Map<String, String> spdxIdProperties) throws InvalidSPDXAnalysisException {
		if (Objects.isNull(jsonNode)) {
			return;
		}
		if (!jsonNode.isArray()) {
			throw new InvalidSPDXAnalysisException("Elements are expected to be in an array for type "+type);
		}
		Iterator<JsonNode> iter = jsonNode.elements();
		while (iter.hasNext()) {
			JsonNode element = iter.next();
			JsonNode idNode = element.get(SpdxConstants.SPDX_IDENTIFIER);
			if (Objects.isNull(idNode) || !idNode.isTextual()) {
				throw new InvalidSPDXAnalysisException("Missing SPDX ID");
			}
			String id = idNode.asText();
			if (Objects.isNull(id) || id.isEmpty()) {
				throw new InvalidSPDXAnalysisException("Missing SPDX ID");
			}
			if (addedElements.containsKey(id)) {
				throw new InvalidSPDXAnalysisException("Duplicate SPDX ID: "+id);
			}
			create(documentUri, id, type);
			restoreObjectPropertyValues(documentUri, id, element, spdxIdProperties);
			addedElements.put(id, new TypedValue(id, type));
		}
	}
	

	/**
	 * Restore the relationships adding them as properites to the correct elements
	 * @param documentNamespace
	 * @param jsonNode
	 * @param addedElements
	 * @throws InvalidSPDXAnalysisException 
	 */
	private void restoreRelationships(String documentNamespace, JsonNode jsonNode,
			Map<String, TypedValue> addedElements) throws InvalidSPDXAnalysisException {
		if (Objects.isNull(jsonNode)) {
			return;
		}
		if (!jsonNode.isArray()) {
			throw new InvalidSPDXAnalysisException("Relationships are expected to be in an array for type Relationship");
		}
		Iterator<JsonNode> iter = jsonNode.elements();
		while (iter.hasNext()) {
			JsonNode relationship = iter.next();
			JsonNode elementIdNode = relationship.get(SpdxConstants.PROP_SPDX_ELEMENTID);
			if (Objects.isNull(elementIdNode) || !elementIdNode.isTextual()) {
				throw new InvalidSPDXAnalysisException("Missing SPDX element ID");
			}
			TypedValue element = addedElements.get(elementIdNode.asText());
			if (Objects.isNull(element)) {
				throw new InvalidSPDXAnalysisException("Missing SPDX element for ID "+elementIdNode.asText());
			}
			JsonNode relationshipTypeNode = relationship.get(SpdxConstants.PROP_RELATIONSHIP_TYPE);
			if (Objects.isNull(relationshipTypeNode) || !relationshipTypeNode.isTextual()) {
				throw new InvalidSPDXAnalysisException("Missing required relationship type");
			}
			String relationshipTypeUri = null;
			try {
				relationshipTypeUri = RelationshipType.valueOf(relationshipTypeNode.asText()).getIndividualURI();
			} catch(Exception ex) {
				throw new InvalidSPDXAnalysisException("Unknown relationship type: "+relationshipTypeNode.asText());
			}
			SimpleUriValue relationshipType = new SimpleUriValue(relationshipTypeUri);
			JsonNode relatedElementNode = relationship.get(SpdxConstants.PROP_RELATED_SPDX_ELEMENT);
			if (Objects.isNull(relatedElementNode) || !relatedElementNode.isTextual()) {
				throw new InvalidSPDXAnalysisException("Missing required related element");
			}
			Object relatedElement = idToObjectValue(documentNamespace, relatedElementNode.asText(), addedElements);
			if (Objects.isNull(relatedElement)) {
				throw new InvalidSPDXAnalysisException("Missing SPDX element for ID "+relatedElementNode.asText());
			}
			String relationshipId = getNextId(IdType.Anonymous, documentNamespace);
			create(documentNamespace, relationshipId, SpdxConstants.CLASS_RELATIONSHIP);
			setValue(documentNamespace, relationshipId, SpdxConstants.PROP_RELATIONSHIP_TYPE, relationshipType);
			setValue(documentNamespace, relationshipId, SpdxConstants.PROP_RELATED_SPDX_ELEMENT, relatedElement);
			addValueToCollection(documentNamespace, element.getId(), SpdxConstants.PROP_RELATIONSHIP, 
					new TypedValue(relationshipId, SpdxConstants.CLASS_RELATIONSHIP));
		}
	}

	/**
	 * Restore all the property values within the JsonNode
	 * @param documentUri
	 * @param id
	 * @param node
	 * @param spdxIdProperties Properties which contain an SPDX ID which needs to be replaced
	 * @throws InvalidSPDXAnalysisException 
	 */
	private void restoreObjectPropertyValues(String documentUri, String id, JsonNode node, 
			Map<String, String> spdxIdProperties) throws InvalidSPDXAnalysisException {
		Iterator<Entry<String, JsonNode>>  fieldIterator = node.fields();
		while (fieldIterator.hasNext()) {
			Entry<String, JsonNode> field = fieldIterator.next();
			if (SKIPPED_PROPERTIES.contains(field.getKey())) {
				continue;
			}
			setPropertyValueForJsonNode(documentUri, id, field.getKey(), field.getValue(), spdxIdProperties, false);
		}
	}

	/**
	 * Set the property value for the property associated with the ID with the value stored in the JsonNode value
	 * @param documentUri document URI
	 * @param id ID of the object to store the value
	 * @param property property name
	 * @param value JSON node containing the value
	 * @param spdxIdProperties Properties which contain an SPDX ID which needs to be replaced
	 * @param list true if this property is a list type
	 * @throws InvalidSPDXAnalysisException
	 */
	private void setPropertyValueForJsonNode(String documentUri, String id, String property, JsonNode value,
			Map<String, String> spdxIdProperties, boolean list) throws InvalidSPDXAnalysisException {
		switch (value.getNodeType()) {
		case ARRAY: {
			Iterator<JsonNode> iter = value.elements();
			while (iter.hasNext()) {
				String individualPropertyName = collectionPropertyNameToPropertyName(property);
				setPropertyValueForJsonNode(documentUri, id, individualPropertyName, iter.next(), spdxIdProperties, true);
			}
		}; break;
		case BOOLEAN: {
			if (list) {
				this.addValueToCollection(documentUri, id, property, value.asBoolean());
			} else {
				this.setValue(documentUri, id, property, value.asBoolean()); 
			}
		} break;
		case NULL: break; // ignore
		case NUMBER: {
			if (list) {
				this.addValueToCollection(documentUri, id, property, value.asInt());
			} else {
				this.setValue(documentUri, id, property, value.asInt()); 
			}
		} break;
		case OBJECT: {
			Optional<String> propertyType = SpdxJsonLDContext.getInstance().getType(property);
			if (!propertyType.isPresent()) {
				throw new InvalidSPDXAnalysisException("Unknown type for property " + property);
			}
			if (SpdxConstants.CLASS_SINGLE_POINTER.equals(propertyType.get())) {
				// need to determine whether a byte or line pointer type
				// A bit of Duck Typing is in order
				if (Objects.nonNull(value.get(SpdxConstants.PROP_POINTER_OFFSET))) {
					propertyType = Optional.of(SpdxConstants.CLASS_POINTER_BYTE_OFFSET_POINTER);
				} else if (Objects.nonNull(value.get(SpdxConstants.PROP_POINTER_LINE_NUMBER))) {
					propertyType = Optional.of(SpdxConstants.CLASS_POINTER_LINE_CHAR_POINTER);
				} else {
					throw new InvalidSPDXAnalysisException("Can not determine type for snippet pointer");
				}
			}
			String objectId = findObjectIdInJsonObject(documentUri, value);
			create(documentUri, objectId, propertyType.get());
			restoreObjectPropertyValues(documentUri, objectId, value, spdxIdProperties);
			if (list) {
				this.addValueToCollection(documentUri, id, property, new TypedValue(objectId, propertyType.get()));
			} else {
				this.setValue(documentUri, id, property, new TypedValue(objectId, propertyType.get()));
			}
		}; break;
		case STRING:
			setStringPropertyValueForJsonNode(documentUri, id, property, value, spdxIdProperties, list); break;
		case BINARY:
		case MISSING:
		case POJO:
		default: throw new InvalidSPDXAnalysisException("Unsupported JSON node type: "+value.toString());
		}
	}

	/**
	 * Set the property value for a string JsonNode
	 * @param documentUri document URI
	 * @param id ID of the object to store the value
	 * @param property property name
	 * @param value JSON node containing the value
	 * @param spdxIdProperties Properties which contain an SPDX ID which needs to be replaced
	 * @param list true if this property is a list type
	 * @throws InvalidSPDXAnalysisException
	 */
	private void setStringPropertyValueForJsonNode(String documentUri, String id, String property, JsonNode value,
			Map<String, String> spdxIdProperties, boolean list) throws InvalidSPDXAnalysisException {
		Optional<String> propertyType = SpdxJsonLDContext.getInstance().getType(property);
		Class<?> clazz = null;
		if (propertyType.isPresent()) {
			clazz = SpdxModelFactory.SPDX_TYPE_TO_CLASS.get(propertyType.get());
		}
		if (Objects.nonNull(clazz)) {
			if (AnyLicenseInfo.class.isAssignableFrom(clazz)) {
				AnyLicenseInfo parsedLicense = LicenseInfoFactory.parseSPDXLicenseString(value.asText(), this, documentUri, null);
				if (list) {
					this.addValueToCollection(documentUri, id, property, new TypedValue(parsedLicense.getId(), parsedLicense.getType()));
				} else {
					this.setValue(documentUri, id, property, new TypedValue(parsedLicense.getId(), parsedLicense.getType()));
				}				
			} else if (SpdxDocument.class.isAssignableFrom(clazz) || ReferenceType.class.isAssignableFrom(clazz)) {
				final String uriValue = value.asText();
				IndividualUriValue individualUriValue = new IndividualUriValue() {

					@Override
					public String getIndividualURI() {
						return uriValue;
					}
					
				};
				if (list) {
					this.addValueToCollection(documentUri, id, property, individualUriValue);
				} else {
					this.setValue(documentUri, id, property, individualUriValue);
				}
			} else if (SpdxElement.class.isAssignableFrom(clazz)) {
				if (list) {
					this.addValueToCollection(documentUri, id, property, value.asText());
				} else {
					this.setValue(documentUri, id, property, value.asText());
				}
				spdxIdProperties.put(id, property);
			} else if (clazz.isEnum()) {					
				for (Object enumConst:clazz.getEnumConstants()) {
					if (enumConst instanceof IndividualUriValue && value.asText().equals(enumConst.toString())) {
						IndividualUriValue iuv = new SimpleUriValue((IndividualUriValue)enumConst);
						if (list) {
							addValueToCollection(documentUri, id, property, iuv);
						} else {
							setValue(documentUri, id, property, iuv);
						}
					}
				}
			} else {
				throw new InvalidSPDXAnalysisException("Unknown type: "+propertyType.get()+" for property "+property);
			}
		} else {
			if (list) {
				this.addValueToCollection(documentUri, id, property, value.asText()); 
			} else {
				this.setValue(documentUri, id, property, value.asText()); 
			}
		}
	}

	/**
	 * @param documentUri
	 * @param jsonObject
	 * @return the ID for the JSON object based on what property values are available
	 * @throws InvalidSPDXAnalysisException
	 */
	private String findObjectIdInJsonObject(String documentUri, JsonNode jsonObject) throws InvalidSPDXAnalysisException {
		JsonNode retval = jsonObject.get(SpdxConstants.SPDX_IDENTIFIER);
		if (Objects.isNull(retval) || !retval.isTextual()) {
			retval = jsonObject.get(SpdxConstants.PROP_LICENSE_ID);
		}
		if (Objects.isNull(retval) || !retval.isTextual()) {
			retval = jsonObject.get(SpdxConstants.PROP_LICENSE_EXCEPTION_ID);
		}
		if (Objects.isNull(retval) || !retval.isTextual()) {
			retval = jsonObject.get(SpdxConstants.EXTERNAL_DOCUMENT_REF_IDENTIFIER);
		}
		if (Objects.isNull(retval) || !retval.isTextual()) {
			return getNextId(IdType.Anonymous, documentUri);
		} else {
			return retval.asText();
		}
	}
}

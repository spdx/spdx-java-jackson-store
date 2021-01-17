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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.jacksonstore.MultiFormatStore.Format;
import org.spdx.jacksonstore.MultiFormatStore.Verbose;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.library.model.ExternalDocumentRef;
import org.spdx.library.model.ExternalSpdxElement;
import org.spdx.library.model.IndividualUriValue;
import org.spdx.library.model.SpdxElement;
import org.spdx.library.model.SpdxInvalidTypeException;
import org.spdx.library.model.SpdxModelFactory;
import org.spdx.library.model.TypedValue;
import org.spdx.library.model.enumerations.SpdxEnumFactory;
import org.spdx.library.model.license.AnyLicenseInfo;
import org.spdx.library.model.license.SimpleLicensingInfo;
import org.spdx.storage.IModelStore;
import org.spdx.storage.IModelStore.IModelStoreLock;
import org.spdx.storage.IModelStore.IdType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Serializer for a model store to convert the document model object into a JsonNode
 * 
 * the method <code>docToJsonNode(String documentUri)</code> will generate the JSON node
 * @author Gary O'Neall
 *
 */
public class JacksonSerializer {
	
	static final Logger logger = LoggerFactory.getLogger(JacksonSerializer.class);
	
	/**
	 * Class to for the name of the XML element to Document
	 *
	 */
	class Document extends ObjectNode {

		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		public Document(JsonNodeFactory nc) {
			super(nc);
		}
		
	}

	private ObjectMapper mapper;
	private IModelStore store;
	private Format format;
	private Verbose verbose;

	/**
	 * @param mapper Jackson Object Mapper to use for creating JSON objects
	 * @param format Format to use
	 * @param store Model store containing the documents
	 */
	public JacksonSerializer(ObjectMapper mapper, Format format, Verbose verbose, IModelStore store) {
		Objects.requireNonNull(mapper, "Null required Jackson mapper");
		Objects.requireNonNull(format, "Null required format");
		Objects.requireNonNull(verbose, "Null required verbose");
		Objects.requireNonNull(store, "Null required store");
		this.mapper = mapper;
		this.store = store;
		this.format = format;
		this.verbose = verbose;
	}

	/**
	 * @param documentUri URI for the document to be converted
	 * @return ObjectNode for an SPDX document in Jackson JSON tree format
	 * @throws InvalidSPDXAnalysisException
	 */
	public ObjectNode docToJsonNode(String documentUri) throws InvalidSPDXAnalysisException {
		Objects.requireNonNull(documentUri,"Null Document URI");
		IModelStoreLock lock = store.enterCriticalSection(documentUri, false);	//TODO: True value causes deadlock due to false value in ExternalDocumentRef line 58
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
			switch (format) {
				case YAML: {
					output = doc;
					break;
				}
				case XML: {
					output = new Document(JsonNodeFactory.instance);
					output.setAll(doc);
					break;
				}
				case JSON: {
					output = doc;
					break;
				}
				case JSON_PRETTY:
				default:  {
					output = doc;
					break;
				}
			}
			return output;
		} finally {
			store.leaveCriticalSection(lock);
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
		List<String> docPropNames = new ArrayList<String>(store.getPropertyValueNames(documentUri, storedItem.getId()));
		docPropNames.sort(new PropertyComparator(storedItem.getType()));
		//TODO - do we sort for all types or just for the document level?
		Class<?> clazz = SpdxModelFactory.SPDX_TYPE_TO_CLASS.get(storedItem.getType());
		IdType idType = store.getIdType(storedItem.getId());
		if (SpdxElement.class.isAssignableFrom(clazz)) {
			if (IdType.SpdxId.equals(idType)) {
				retval.put(SpdxConstants.SPDX_IDENTIFIER, storedItem.getId());
			} else if (!IdType.Anonymous.equals(idType)) {
				logger.error("Invalid ID "+storedItem.getId()+".  Must be an SPDX Identifier or Anonomous");
				throw new InvalidSPDXAnalysisException("Invalid ID "+storedItem.getId()+".  Must be an SPDX Identifier or Anonomous");
			}
		} else if (ExternalDocumentRef.class.isAssignableFrom(clazz)) {
			retval.put(SpdxConstants.EXTERNAL_DOCUMENT_REF_IDENTIFIER, storedItem.getId());
		} else if (SimpleLicensingInfo.class.isAssignableFrom(clazz)) {
			retval.put(SpdxConstants.PROP_LICENSE_ID, storedItem.getId());
		}
		for (String propertyName:docPropNames) {
			if (SpdxConstants.PROP_RELATIONSHIP.equals(propertyName)) {
				for (ObjectNode relationship:toJsonRelationships(documentUri, storedItem.getId(), store.listValues(documentUri, storedItem.getId(), SpdxConstants.PROP_RELATIONSHIP))) {
					relationships.add(relationship);
				}
			} else if (SpdxConstants.PROP_SPDX_EXTRACTED_LICENSES.equals(propertyName)) {
				retval.set(MultiFormatStore.propertyNameToCollectionPropertyName(propertyName), 
						toExtractedLicensesArrayNode(documentUri, storedItem.getId(), propertyName, relationships));
			} else if (store.isCollectionProperty(documentUri, storedItem.getId(), propertyName)) {
				ArrayNode valuesArray = toArrayNode(documentUri, store.listValues(documentUri, storedItem.getId(), propertyName), 
						relationships);
				retval.set(MultiFormatStore.propertyNameToCollectionPropertyName(propertyName), 
						valuesArray);
			} else {
				Optional<Object> value = store.getValue(documentUri, storedItem.getId(), propertyName);
				if (value.isPresent()) {
					setValueField(retval, propertyName, documentUri, value.get(), relationships);
				}
			}
		}
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
	 * @param documentUri Document namespace or Uri
	 * @param type type of document element to get (Package, File, or Snippet)
	 * @return JsonArray of document elements matching the type
	 * @throws InvalidSPDXAnalysisException
	 */
	private ArrayNode getDocElements(String documentUri, String type, ArrayNode relationships) throws InvalidSPDXAnalysisException {
		return store.getAllItems(documentUri, type).collect(() -> mapper.createArrayNode(), (an,  item) ->
				{
					try {
						an.add(typedValueToObjectNode(documentUri, item, relationships));
					} catch (InvalidSPDXAnalysisException e) {
						throw new RuntimeException(e);
					}
				}, (an1, an2) -> {
					an1.addAll(an2);
				});
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
		Iterator<Object> extractedLicenses = store.listValues(documentUri, id, propertyName);
		while (extractedLicenses.hasNext()) {
			Object extractedLicense = extractedLicenses.next();
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
			Iterator<Object> valueList) throws InvalidSPDXAnalysisException {
		ArrayList<ObjectNode> retval = new ArrayList<>();
		while (valueList.hasNext()) {
			Object value = valueList.next();
			if (!(value instanceof TypedValue)) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+value.getClass().toString());
			}
			TypedValue tvValue = (TypedValue)value;
			if (!SpdxConstants.CLASS_RELATIONSHIP.equals(tvValue.getType())) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+tvValue.getType());
			}
			ObjectNode relationship = mapper.createObjectNode();
			relationship.put(SpdxConstants.PROP_SPDX_ELEMENTID, id);
			Optional<Object> relatedSpdxElement = store.getValue(documentUri, tvValue.getId(), SpdxConstants.PROP_RELATED_SPDX_ELEMENT); 
			if (!relatedSpdxElement.isPresent()) {
				logger.warn("Missing related SPDX element for a relationship for "+id+".  Skipping the serialization of this relationship.");
				continue;
			}
			if (relatedSpdxElement.get() instanceof TypedValue) {
				String relatedElementId = ((TypedValue)relatedSpdxElement.get()).getId();
				relationship.put(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, relatedElementId);
			} else if (relatedSpdxElement.get() instanceof IndividualUriValue) {
				String externalUri = ((IndividualUriValue)relatedSpdxElement.get()).getIndividualURI();
				if (SpdxConstants.URI_VALUE_NONE.equals(externalUri)) {
					relationship.put(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, SpdxConstants.NONE_VALUE);
				} else if (SpdxConstants.URI_VALUE_NOASSERTION.equals(externalUri)) {
					relationship.put(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, SpdxConstants.NOASSERTION_VALUE);
				} else if (SpdxConstants.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(externalUri).matches()) {
					// external SPDX element
					ExternalSpdxElement externalElement = ExternalSpdxElement.uriToExternalSpdxElement(externalUri, store, documentUri, null);
					relationship.put(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, externalElement.getExternalDocumentId() + ":" + externalElement.getExternalElementId());
				} else {
					throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement, SpdxNoneElement, SpdxNoAssertionElement or external SPDX element type.  URI does not match pattern for external element: "+externalUri);
				}
			}  else {
				throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement or external SPDX element type.  Found type "+relatedSpdxElement.get().getClass().toString());
			}
			
			Optional<Object> type = store.getValue(documentUri, tvValue.getId(), SpdxConstants.PROP_RELATIONSHIP_TYPE);
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
	 * Convert a list of values to an ArrayNode for serialization
	 * @param documentUri Document namespace or Uri
	 * @param valueList list of values to convert
	 * @param relationships running total of any relationships found - any relationships are added
	 * @return
	 * @throws InvalidSPDXAnalysisException 
	 */
	private ArrayNode toArrayNode(String documentUri, Iterator<Object> valueList, ArrayNode relationships) throws InvalidSPDXAnalysisException {
		ArrayNode retval = mapper.createArrayNode();
		while (valueList.hasNext()) {
			Object value = valueList.next();
			addValueToArrayNode(retval, documentUri, value, relationships);
		}
		return retval;
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
				AnyLicenseInfo ali = (AnyLicenseInfo)SpdxModelFactory.createModelObject(store, documentUri, 
						tvStoredValue.getId(), tvStoredValue.getType(), null);
				return ali.toString();
			} else if (SpdxElement.class.isAssignableFrom(clazz) &&
					Verbose.COMPACT.equals(verbose) &&
					!IModelStore.IdType.Anonymous.equals(store.getIdType(tvStoredValue.getId()))) {
				return tvStoredValue.getId();
			} else {
				return typedValueToObjectNode(documentUri, (TypedValue)value, relationships);
			}
		} else {
			return value;
		}
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
			ExternalSpdxElement externalElement = ExternalSpdxElement.uriToExternalSpdxElement(uri, store, documentUri, null);
			return externalElement.getExternalDocumentId() + ":" + externalElement.getExternalElementId();
		} else if (SpdxConstants.URI_VALUE_NONE.equals(uri)) {
			return SpdxConstants.NONE_VALUE;
		} else if (SpdxConstants.URI_VALUE_NOASSERTION.equals(uri)) {
			return SpdxConstants.NOASSERTION_VALUE;
		} else {
			return uri;
		}
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
}

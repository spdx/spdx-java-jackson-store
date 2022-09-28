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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
import org.spdx.library.model.enumerations.RelationshipType;
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
			//Remove duplicate relationships
			Map<String, Map<String, Set<String>>> iDRelTypeRelatediDMap = new HashMap<>();
			Iterator<JsonNode> relIter = relationships.elements();
			ArrayNode deDupedRelationships = new ArrayNode(JsonNodeFactory.instance);
			while (relIter.hasNext()) {
				JsonNode relationship = relIter.next();
				String id = relationship.get(SpdxConstants.PROP_SPDX_ELEMENTID).asText();
				String relType = relationship.get(SpdxConstants.PROP_RELATIONSHIP_TYPE).asText();
				String relatedID = relationship.get(SpdxConstants.PROP_RELATED_SPDX_ELEMENT).asText();
				Map<String, Set<String>> relTypeRelatedIdMap = iDRelTypeRelatediDMap.get(id);
				if (Objects.isNull(relTypeRelatedIdMap)) {
					relTypeRelatedIdMap = new HashMap<>();
					iDRelTypeRelatediDMap.put(id, relTypeRelatedIdMap);
				}
				Set<String> relatedIds = relTypeRelatedIdMap.get(relType);
				if (Objects.isNull(relatedIds)) {
					relatedIds = new HashSet<>();
					relTypeRelatedIdMap.put(relType, relatedIds);
				}
				if (!relatedIds.contains(relatedID)) {
					deDupedRelationships.add(relationship);
					relatedIds.add(relatedID);
				}
			}
			
			
			doc.set(SpdxConstants.PROP_DOCUMENT_RELATIONSHIPS, deDupedRelationships);
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
	private ObjectNode typedValueToObjectNode(String documentUri, TypedValue storedItem, 
			ArrayNode relationships) throws InvalidSPDXAnalysisException {
		ObjectNode retval = mapper.createObjectNode();
		Set<String> hasFileIds = new HashSet<>(); // keep track of any hasFile properties added to avoid duplicates
		Set<String> documentDescribesIds = new HashSet<>(); // keep track of any documentDescribes properties added to avoid duplicates
		List<String> docPropNames = new ArrayList<String>(store.getPropertyValueNames(documentUri, storedItem.getId()));
		docPropNames.sort(new PropertyComparator(storedItem.getType()));
		Class<?> clazz = SpdxModelFactory.SPDX_TYPE_TO_CLASS.get(storedItem.getType());
		IdType idType = store.getIdType(storedItem.getId());
		if (SpdxElement.class.isAssignableFrom(clazz)) {
			if (IdType.SpdxId.equals(idType)) {
				retval.put(SpdxConstants.SPDX_IDENTIFIER, storedItem.getId());
			} else if (!IdType.Anonymous.equals(idType)) {
				logger.error("Invalid ID "+storedItem.getId()+".  Must be an SPDX Identifier or Anonymous");
				throw new InvalidSPDXAnalysisException("Invalid ID "+storedItem.getId()+".  Must be an SPDX Identifier or Anonymous");
			}
		} else if (ExternalDocumentRef.class.isAssignableFrom(clazz)) {
			retval.put(SpdxConstants.EXTERNAL_DOCUMENT_REF_IDENTIFIER, storedItem.getId());
		} else if (SimpleLicensingInfo.class.isAssignableFrom(clazz)) {
			retval.put(SpdxConstants.PROP_LICENSE_ID, storedItem.getId());
		}
		for (String propertyName:docPropNames) {
			if (SpdxConstants.PROP_RELATIONSHIP.equals(propertyName)) {
				addJsonRelationships(documentUri, storedItem, retval, relationships, hasFileIds, documentDescribesIds);
			} else if (SpdxConstants.PROP_SPDX_EXTRACTED_LICENSES.equals(propertyName)) {
				retval.set(MultiFormatStore.propertyNameToCollectionPropertyName(propertyName), 
						toExtractedLicensesArrayNode(documentUri, storedItem.getId(), propertyName, relationships));
			} else if (store.isCollectionProperty(documentUri, storedItem.getId(), propertyName)) {
				Iterator<Object> propertyValues = store.listValues(documentUri, storedItem.getId(), propertyName);
				ArrayNode valuesArray;
				if (SpdxConstants.PROP_PACKAGE_FILE.equals(propertyName) && hasFileIds.size() > 0) {
					// Need to filter out existing hasFileIds
					List<Object> hasFilesToAdd = new ArrayList<>();
					while (propertyValues.hasNext()) {
						Object fileToAdd = propertyValues.next();
						if (fileToAdd instanceof TypedValue) {
							String fileToAddId = ((TypedValue)fileToAdd).getId();
							if (!hasFileIds.contains(fileToAddId)) {
								hasFilesToAdd.add(fileToAdd);
								hasFileIds.add(fileToAddId);
							}
						} else {
							hasFilesToAdd.add(fileToAdd);
						}
					}
					valuesArray = toArrayNode(documentUri, hasFilesToAdd.iterator(), 
							relationships);
					
				} else if (SpdxConstants.PROP_DOCUMENT_DESCRIBES.equals(propertyName) && documentDescribesIds.size() > 0) {
					// Need to filter out existing documentDescribes
					List<Object> describesToAdd = new ArrayList<>();
					while (propertyValues.hasNext()) {
						Object describedElementToAdd = propertyValues.next();
						if (describedElementToAdd instanceof TypedValue) {
							String describedElementToAddId = ((TypedValue)describedElementToAdd).getId();
							if (!hasFileIds.contains(describedElementToAddId)) {
								describesToAdd.add(describedElementToAdd);
								hasFileIds.add(describedElementToAddId);
							}
						} else {
							describesToAdd.add(describedElementToAdd);
						}
					}
					valuesArray = toArrayNode(documentUri, describesToAdd.iterator(), 
							relationships);
				} else {
					valuesArray = toArrayNode(documentUri, propertyValues, 
							relationships);
				}
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
	 * Add the JSON relationships for an element
	 * @param documentUri Document namespace or Uri
	 * @param element element containing the relationship
	 * @param elementNode the JSON node for the serialized element
	 * @param valueList List of all relationships values
	 * @param relationships ArrayNode of relationships to add any found relationships
	 * @param hasFileIds set of hasFile property file Ids added to the element - used to avoid duplicates
	 * @param documentDescribesids set of documentDescribes element IDs added to the element - used to avoid duplicates
	 * @throws InvalidSPDXAnalysisException
	 */
	private void addJsonRelationships(String documentUri, TypedValue element, ObjectNode elementNode,
			ArrayNode relationships, Set<String> hasFileIds, Set<String> documentDescribesIds) throws InvalidSPDXAnalysisException {
		Iterator<Object> valueList = store.listValues(documentUri, element.getId(), 
				SpdxConstants.PROP_RELATIONSHIP);
		List<String> hasFileIdsToAdd = new ArrayList<>();
		List<String> documentDescribesIdsToAdd = new ArrayList<>();
		while (valueList.hasNext()) {
			Object value = valueList.next();
			if (!(value instanceof TypedValue)) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+value.getClass().toString());
			}
			TypedValue tvValue = (TypedValue)value;
			if (!SpdxConstants.CLASS_RELATIONSHIP.equals(tvValue.getType())) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+tvValue.getType());
			}
			Optional<Object> relatedSpdxElement = store.getValue(documentUri, tvValue.getId(), SpdxConstants.PROP_RELATED_SPDX_ELEMENT); 
			if (!relatedSpdxElement.isPresent()) {
				logger.warn("Missing related SPDX element for a relationship for "+element.getId()+".  Skipping the serialization of this relationship.");
				continue;
			}
			String relatedElementId;
			String relatedElementType;
			if (relatedSpdxElement.get() instanceof TypedValue) {
				relatedElementId = ((TypedValue)relatedSpdxElement.get()).getId();
				relatedElementType = ((TypedValue)relatedSpdxElement.get()).getType();
			} else if (relatedSpdxElement.get() instanceof IndividualUriValue) {
				String externalUri = ((IndividualUriValue)relatedSpdxElement.get()).getIndividualURI();
				if (SpdxConstants.URI_VALUE_NONE.equals(externalUri)) {
					relatedElementId = SpdxConstants.NONE_VALUE;
					relatedElementType = SpdxConstants.CLASS_SPDX_NONE_ELEMENT;
				} else if (SpdxConstants.URI_VALUE_NOASSERTION.equals(externalUri)) {
					relatedElementId = SpdxConstants.NOASSERTION_VALUE;
					relatedElementType = SpdxConstants.CLASS_SPDX_NOASSERTION_ELEMENT;
				} else if (SpdxConstants.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(externalUri).matches()) {
					// external SPDX element
					ExternalSpdxElement externalElement = ExternalSpdxElement.uriToExternalSpdxElement(externalUri, store, documentUri, null);
					relatedElementId = externalElement.getExternalDocumentId() + ":" + externalElement.getExternalElementId();
					relatedElementType = SpdxConstants.CLASS_EXTERNAL_SPDX_ELEMENT;
				} else {
					throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement, SpdxNoneElement, SpdxNoAssertionElement or external SPDX element type.  URI does not match pattern for external element: "+externalUri);
				}
			}  else {
				throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement or external SPDX element type.  Found type "+relatedSpdxElement.get().getClass().toString());
			}
			
			Optional<Object> relationshipType = store.getValue(documentUri, tvValue.getId(), SpdxConstants.PROP_RELATIONSHIP_TYPE);
			if (!relationshipType.isPresent()) {
				logger.warn("Missing type for a relationship for "+element.getId()+".  Skipping the serialization of this relationship.");
				continue;
			}
			if (!(relationshipType.get() instanceof IndividualUriValue)) {
				throw new SpdxInvalidTypeException("Expected RelationshipType type for relationshipType property.  Unexpected type "+relatedSpdxElement.get().getClass().toString());
			}
			String relationshipTypeStr = individualUriToString(documentUri, ((IndividualUriValue)relationshipType.get()).getIndividualURI());
			if (RelationshipType.DESCRIBES.toString().equals(relationshipTypeStr) && 
					SpdxConstants.CLASS_SPDX_DOCUMENT.equals(element.getType()) &&
					!documentDescribesIds.contains(relatedElementId)) {
				// This needs to be added as a documentDescribes property and not as a relationship
				documentDescribesIdsToAdd.add(relatedElementId);
				documentDescribesIds.add(relatedElementId);
			} else if (RelationshipType.CONTAINS.toString().equals(relationshipTypeStr) && 
					SpdxConstants.CLASS_SPDX_PACKAGE.equals(element.getType()) &&
					SpdxConstants.CLASS_SPDX_FILE.equals(relatedElementType) &&
					!hasFileIds.contains(relatedElementId)) {
				// This needs to be added as a hasFiles property
				hasFileIdsToAdd.add(relatedElementId);
				hasFileIds.add(relatedElementId);
			} else {
				ObjectNode relationship = mapper.createObjectNode();
				relationship.put(SpdxConstants.PROP_SPDX_ELEMENTID, element.getId());
				relationship.put(SpdxConstants.PROP_RELATIONSHIP_TYPE, relationshipTypeStr);
				relationship.put(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, relatedElementId);
				relationships.add(relationship);
			}
			if (documentDescribesIdsToAdd.size() > 0) {
				JsonNode jnDescribes = elementNode.get(SpdxConstants.PROP_DOCUMENT_DESCRIBES);
				if (Objects.isNull(jnDescribes)) {
					jnDescribes = mapper.createArrayNode();
					elementNode.set(SpdxConstants.PROP_DOCUMENT_DESCRIBES, jnDescribes);
				}
				if (!(jnDescribes instanceof ArrayNode)) {
					throw new InvalidSPDXAnalysisException("Describes is not an array");
				}
				for (String describesId:documentDescribesIdsToAdd) {
					((ArrayNode)jnDescribes).add(describesId);
				}
			}
			if (hasFileIdsToAdd.size() > 0) {
				JsonNode jnFiles = elementNode.get(
						MultiFormatStore.propertyNameToCollectionPropertyName(SpdxConstants.PROP_PACKAGE_FILE));
				if (Objects.isNull(jnFiles)) {
					jnFiles = mapper.createArrayNode();
					elementNode.set(
							MultiFormatStore.propertyNameToCollectionPropertyName(SpdxConstants.PROP_PACKAGE_FILE),
							jnFiles);
				}
				if (!(jnFiles instanceof ArrayNode)) {
					throw new InvalidSPDXAnalysisException("hasFile is not an array");
				}
				for (String hasFileId:hasFileIdsToAdd) {
					((ArrayNode)jnFiles).add(hasFileId);
				}
			}
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
			if (enumval instanceof RelationshipType) {
				return enumval.toString();
			} else {
				return enumval.toString().replaceAll("_", "-");
			}
		} else if (SpdxConstants.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(uri).matches()) {
			ExternalSpdxElement externalElement = ExternalSpdxElement.uriToExternalSpdxElement(uri, store, documentUri, null);
			return externalElement.getExternalDocumentId() + ":" + externalElement.getExternalElementId();
		} else if (SpdxConstants.URI_VALUE_NONE.equals(uri)) {
			return SpdxConstants.NONE_VALUE;
		} else if (SpdxConstants.URI_VALUE_NOASSERTION.equals(uri)) {
			return SpdxConstants.NOASSERTION_VALUE;
		} else if (uri.startsWith(SpdxConstants.SPDX_LISTED_REFERENCE_TYPES_PREFIX)) {
		    return uri.substring(SpdxConstants.SPDX_LISTED_REFERENCE_TYPES_PREFIX.length());
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

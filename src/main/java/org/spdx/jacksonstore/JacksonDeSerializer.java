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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.spdx.core.IndividualUriValue;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.core.ModelObjectHelper;
import org.spdx.core.SimpleUriValue;
import org.spdx.core.TypedValue;
import org.spdx.jacksonstore.MultiFormatStore.Format;
import org.spdx.library.LicenseInfoFactory;
import org.spdx.library.model.v2.ExternalSpdxElement;
import org.spdx.library.model.v2.ReferenceType;
import org.spdx.library.model.v2.SpdxConstantsCompatV2;
import org.spdx.library.model.v2.SpdxDocument;
import org.spdx.library.model.v2.SpdxElement;
import org.spdx.library.model.v2.SpdxModelFactoryCompatV2;
import org.spdx.library.model.v2.enumerations.RelationshipType;
import org.spdx.library.model.v2.license.AnyLicenseInfo;
import org.spdx.library.referencetype.ListedReferenceTypes;
import org.spdx.storage.IModelStore;
import org.spdx.storage.IModelStore.IModelStoreLock;
import org.spdx.storage.IModelStore.IdType;
import org.spdx.storage.compatv2.CompatibleModelStoreWrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;

/**
 * Converts a Jackson node for a document to a stored document in a model store
 * 
 * @author Gary O'Neall
 *
 */
public class JacksonDeSerializer {
	
	/**
	 * Property names that should not be restored as part of the deserialization
	 */
	static final Set<String> SKIPPED_PROPERTIES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(new String[] {
			SpdxConstantsCompatV2.PROP_DOCUMENT_PACKAGES.getName(), 
			SpdxConstantsCompatV2.PROP_DOCUMENT_FILES.getName(),
			SpdxConstantsCompatV2.PROP_DOCUMENT_SNIPPETS.getName(), 
			SpdxConstantsCompatV2.SPDX_IDENTIFIER, 
			SpdxConstantsCompatV2.PROP_DOCUMENT_RELATIONSHIPS.getName(),
			SpdxConstantsCompatV2.PROP_DOCUMENT_NAMESPACE.getName()
	})));

	private CompatibleModelStoreWrapper store;
	@SuppressWarnings("unused")
	private Format format;
	private Map<String, Map<String, Map<SimpleUriValue, String>>> addedRelationships = new HashMap<>();

	/**
	 * @param store store to store any documents in
	 */
	public JacksonDeSerializer(IModelStore store, Format format) {
		Objects.requireNonNull(store, "Model store can not be null");
		Objects.requireNonNull(format, "Format can not be null");
		this.store = new CompatibleModelStoreWrapper(store);
		this.format = format;
	}

	/**
	 * Stores an SPDX document converted from the JsonNode doc
	 * @param documentNamespace namespace for the document
	 * @param doc JsonNode containing the SPDX document
	 * @throws InvalidSPDXAnalysisException
	 */
	@SuppressWarnings("unchecked")
	public void storeDocument(String documentNamespace, JsonNode doc) throws InvalidSPDXAnalysisException {
		Objects.requireNonNull(documentNamespace, "Null required document namespace");
		Objects.requireNonNull(doc, "Null document JSON Node");
		IModelStoreLock lock = store.enterCriticalSection(documentNamespace, false);
		try {
			Map<String, String> spdxIdProperties = new HashMap<>();	// properties which contain an SPDX id which needs to be replaced
			store.create(documentNamespace, SpdxConstantsCompatV2.SPDX_DOCUMENT_ID, SpdxConstantsCompatV2.CLASS_SPDX_DOCUMENT);
			restoreObjectPropertyValues(documentNamespace, SpdxConstantsCompatV2.SPDX_DOCUMENT_ID, doc, spdxIdProperties);
			// restore the packages
			Map<String, TypedValue> addedElements = new HashMap<>();
			addedElements.put(SpdxConstantsCompatV2.SPDX_DOCUMENT_ID, 
					CompatibleModelStoreWrapper.typedValueFromDocUri(documentNamespace, SpdxConstantsCompatV2.SPDX_DOCUMENT_ID, false, SpdxConstantsCompatV2.CLASS_SPDX_DOCUMENT));
			restoreElements(documentNamespace, SpdxConstantsCompatV2.CLASS_SPDX_PACKAGE, 
					doc.get(SpdxConstantsCompatV2.PROP_DOCUMENT_PACKAGES.getName()), addedElements, spdxIdProperties);
			restoreElements(documentNamespace, SpdxConstantsCompatV2.CLASS_SPDX_FILE, 
					doc.get(SpdxConstantsCompatV2.PROP_DOCUMENT_FILES.getName()), addedElements, spdxIdProperties);
			restoreElements(documentNamespace, SpdxConstantsCompatV2.CLASS_SPDX_SNIPPET, 
					doc.get(SpdxConstantsCompatV2.PROP_DOCUMENT_SNIPPETS.getName()), addedElements, spdxIdProperties);
			restoreRelationships(documentNamespace, doc.get(SpdxConstantsCompatV2.PROP_DOCUMENT_RELATIONSHIPS.getName()),
					addedElements);
			// fix up the ID's
			for (Entry<String, String> propertyToFix:spdxIdProperties.entrySet()) {
				Optional<Object> idToReplace = store.getValue(documentNamespace, propertyToFix.getKey(), propertyToFix.getValue());
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
					store.clearValueCollection(documentNamespace, propertyToFix.getKey(), propertyToFix.getValue());
					for (Object replacement:replacements) {
						store.addValueToCollection(documentNamespace, propertyToFix.getKey(), propertyToFix.getValue(), replacement);
					}
					
				} else {
					if (!(idToReplace.get() instanceof String)) {
						throw new InvalidSPDXAnalysisException("Can not replace the SPDX ID with value due to invalid type for "+propertyToFix.getKey() + " " + propertyToFix.getValue());
					}
					String spdxId = (String)idToReplace.get();
					store.setValue(documentNamespace, propertyToFix.getKey(), propertyToFix.getValue(), idToObjectValue(documentNamespace, spdxId, addedElements));
				}
			}
		} finally {
			store.leaveCriticalSection(lock);
		}
	}
	
	/**
	 * Restores a single SPDX element of a specific type
	 * @param documentUri
	 * @param type
	 * @param jsonNode
	 * @param addedElements
	 * @param spdxIdProperties Properties which contain an SPDX ID which needs to be replaced
	 * @throws InvalidSPDXAnalysisException
	 */
	private void restoreElement(String documentUri, String type, @Nullable JsonNode jsonNode,
			Map<String, TypedValue> addedElements, Map<String, String> spdxIdProperties) throws InvalidSPDXAnalysisException {
		if (Objects.isNull(jsonNode)) {
			return;
		}
		if (!jsonNode.isObject()) {
			throw new InvalidSPDXAnalysisException("Invalid JSON node type for SPDX element");
		}
		JsonNode idNode = jsonNode.get(SpdxConstantsCompatV2.SPDX_IDENTIFIER);
		if (Objects.isNull(idNode) || !idNode.isTextual()) {
			throw new InvalidSPDXAnalysisException("Missing SPDX ID for type "+type);
		}
		String id = idNode.asText();
		if (Objects.isNull(id) || id.isEmpty()) {
			throw new InvalidSPDXAnalysisException("Empty SPDX ID for type "+type);
		}
		if (addedElements.containsKey(id)) {
			throw new InvalidSPDXAnalysisException("Duplicate SPDX ID: "+id);
		}
		store.create(documentUri, id, type);
		try {
			restoreObjectPropertyValues(documentUri, id, jsonNode, spdxIdProperties);
		} catch(InvalidSPDXAnalysisException ex) {
			// Add more information to the error message
			throw new InvalidSPDXAnalysisException("Error parsing JSON field for ID "+id+": "+ex.getMessage(), ex);
		}
		addedElements.put(id, CompatibleModelStoreWrapper.typedValueFromDocUri(documentUri, id, store, type));
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
		if (jsonNode.isArray()) {
			Iterator<JsonNode> iter = jsonNode.elements();
			while (iter.hasNext()) {
				restoreElement(documentUri, type, iter.next(), addedElements, spdxIdProperties);
			}
		} else {
			// This can occur if there is only a single element in an XML document
			restoreElement(documentUri, type, jsonNode, addedElements, spdxIdProperties);
		}
	}
	

	/**
	 * Restore the relationships adding them as properties to the correct elements
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
		if (jsonNode.isArray()) {
			Iterator<JsonNode> iter = jsonNode.elements();
			while (iter.hasNext()) {
				restoreRelationship(documentNamespace, iter.next(), addedElements);
			}
		} else {
			// This can occur if there is only a single relationship in an XML document
			restoreRelationship(documentNamespace, jsonNode, addedElements);
		}
	}

	/**
	 * Restore a relationship adding it as properties to the correct elements
	 * @param documentNamespace
	 * @param relationship
	 * @param addedElements
	 * @throws InvalidSPDXAnalysisException
	 */
	private void restoreRelationship(String documentNamespace, JsonNode relationship,
																	 Map<String, TypedValue> addedElements) throws InvalidSPDXAnalysisException {
		JsonNode elementIdNode = relationship.get(SpdxConstantsCompatV2.PROP_SPDX_ELEMENTID.getName());
		if (Objects.isNull(elementIdNode) || !elementIdNode.isTextual()) {
			throw new InvalidSPDXAnalysisException("Missing SPDX element ID");
		}
		TypedValue element = addedElements.get(elementIdNode.asText());
		if (Objects.isNull(element)) {
			throw new InvalidSPDXAnalysisException("Missing SPDX element for ID "+elementIdNode.asText());
		}
		JsonNode relationshipTypeNode = relationship.get(SpdxConstantsCompatV2.PROP_RELATIONSHIP_TYPE.getName());
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
		JsonNode relatedElementNode = relationship.get(SpdxConstantsCompatV2.PROP_RELATED_SPDX_ELEMENT.getName());
		if (Objects.isNull(relatedElementNode) || !relatedElementNode.isTextual()) {
			throw new InvalidSPDXAnalysisException("Missing required related element");
		}
		Object relatedElement = idToObjectValue(documentNamespace, relatedElementNode.asText(), addedElements);
		JsonNode commentNode = relationship.get(SpdxConstantsCompatV2.RDFS_PROP_COMMENT.getName());
		Optional<String> relationshipComment;
		if (Objects.isNull(commentNode) || !commentNode.isTextual()) {
			relationshipComment = Optional.empty();
		} else {
			relationshipComment = Optional.of(commentNode.asText());
		}
		addRelationship(documentNamespace, CompatibleModelStoreWrapper.objectUriToId(store, element.getObjectUri(), documentNamespace),
				relationshipType, relatedElement, relationshipComment);
	}

	/**
	 * Add a relationship to the element with ID elementId checking for duplicates
	 * @param documentNamespace documentNamespace
	 * @param elementId ID of the element containing the relationship
	 * @param relationshipType relationshipType
	 * @param relatedElement related element
	 * @param relationshipComment optional comment for the relationship
	 * @return the ID of the relationship
	 * @throws InvalidSPDXAnalysisException 
	 */
	private String addRelationship(String documentNamespace, String elementId, SimpleUriValue relationshipType, Object relatedElement, Optional<String> relationshipComment) throws InvalidSPDXAnalysisException {
        String relatedElementId;
        if (relatedElement instanceof TypedValue) {
            relatedElementId = CompatibleModelStoreWrapper.objectUriToId(store, ((TypedValue)relatedElement).getObjectUri(), documentNamespace);
        } else if (relatedElement instanceof String) {
            relatedElementId = (String)relatedElement;
        } else if (relatedElement instanceof IndividualUriValue) {
            relatedElementId = ((IndividualUriValue)relatedElement).getIndividualURI();
        } else {
            throw new InvalidSPDXAnalysisException("Related element is not of an Element type for relationship to element "+elementId);
        }
	    // check for duplicates
	    Map<SimpleUriValue, String> relatedElementRelationships = null;
	    Map<String, Map<SimpleUriValue, String>> elementRelationships = addedRelationships.get(elementId);
	    if (Objects.nonNull(elementRelationships)) {
	        relatedElementRelationships = elementRelationships.get(relatedElementId);
	        if (Objects.nonNull(relatedElementRelationships)) {
	            String relationshipId = relatedElementRelationships.get(relationshipType);
	            if (Objects.nonNull(relationshipId)) {
	                return relationshipId;
	            }
	        } else {
	            relatedElementRelationships = new HashMap<>();
	            elementRelationships.put(relatedElementId, relatedElementRelationships);
	        }
	    } else {
	        elementRelationships = new HashMap<>();
	        relatedElementRelationships = new HashMap<>();
            elementRelationships.put(relatedElementId, relatedElementRelationships);
            addedRelationships.put(elementId, elementRelationships);
	    }
		String relationshipId = store.getNextId(IdType.Anonymous, documentNamespace);
		store.create(documentNamespace, relationshipId, SpdxConstantsCompatV2.CLASS_RELATIONSHIP);
		store.setValue(documentNamespace, relationshipId, SpdxConstantsCompatV2.PROP_RELATIONSHIP_TYPE, relationshipType);
		store.setValue(documentNamespace, relationshipId, SpdxConstantsCompatV2.PROP_RELATED_SPDX_ELEMENT, relatedElement);
		store.addValueToCollection(documentNamespace, elementId, SpdxConstantsCompatV2.PROP_RELATIONSHIP, 
				CompatibleModelStoreWrapper.typedValueFromDocUri(documentNamespace, relationshipId, store, SpdxConstantsCompatV2.CLASS_RELATIONSHIP));
		if (relationshipComment.isPresent()) {
			store.setValue(documentNamespace, relationshipId, SpdxConstantsCompatV2.RDFS_PROP_COMMENT, relationshipComment.get());
		}
		relatedElementRelationships.put(relationshipType, relationshipId);
		return relationshipId;
	}

	/**
	 * Restore all the property values within the JsonNode
	 * @param documentUri SPDX Document URI
	 * @param id ID for the element containing the properties
	 * @param node Node containing the property values
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
			} else if (SpdxConstantsCompatV2.PROP_DOCUMENT_DESCRIBES.getName().equals(field.getKey())) {
				// These needs to be converted to a DocumentDescribes relationship
				convertFieldToRelationship(documentUri, id, field.getValue(), 
						RelationshipType.DESCRIBES, spdxIdProperties);
			} else if (SpdxConstantsCompatV2.PROP_PACKAGE_FILE.getName().equals(
					MultiFormatStore.collectionPropertyNameToPropertyName(field.getKey()))) {
				// These needs to be converted to a CONTAINSS relationship
				convertFieldToRelationship(documentUri, id, field.getValue(), 
						RelationshipType.CONTAINS, spdxIdProperties);
			} else {
			    setPropertyValueForJsonNode(documentUri, id, field.getKey(), field.getValue(), spdxIdProperties, false);
			}
		}
	}
	
	

	/**
	 * Converts a field value to an equivalent relationship
	 * @param documentUri SPDX Document URI
	 * @param id ID for the element containing the properties
	 * @param spdxIdField Either a single SPDX ID or an array of SPDX IDs
	 * @param relationshipType Relationship type to use
	 * @param spdxIdProperties properties to add the relationship to
	 * @throws InvalidSPDXAnalysisException 
	 */
	private void convertFieldToRelationship(String documentUri, String id, JsonNode spdxIdField,
			RelationshipType relationshipType, Map<String, String> spdxIdProperties) throws InvalidSPDXAnalysisException {
		if ((spdxIdField instanceof ArrayNode)) {
			for (JsonNode spdxidNode:((ArrayNode)spdxIdField)) {		
				String relationshipId = addRelationship(documentUri, id, 
						new SimpleUriValue(relationshipType.getIndividualURI()), 
						spdxidNode.asText(), Optional.empty());
				spdxIdProperties.put(relationshipId, SpdxConstantsCompatV2.PROP_RELATED_SPDX_ELEMENT.getName()); // Add the SPDX ID to the list to be translated back to elements later
			}					
		} else {
			String relationshipId = addRelationship(documentUri, id, 
					new SimpleUriValue(relationshipType.getIndividualURI()), 
					spdxIdField.asText(), Optional.empty());
			spdxIdProperties.put(relationshipId, SpdxConstantsCompatV2.PROP_RELATED_SPDX_ELEMENT.getName()); // Add the SPDX ID to the list to be translated back to elements later
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
		if (SpdxJsonLDContext.getInstance().isList(property)) {
			list = true;
		}
		if (JsonNodeType.ARRAY.equals(value.getNodeType())) {
			Iterator<JsonNode> iter = value.elements();
			while (iter.hasNext()) {
				setPropertyValueForJsonNode(documentUri, id, property, iter.next(), spdxIdProperties, true);
			}
		} else if (!JsonNodeType.NULL.equals(value.getNodeType())) {
			// ignore te null;
			Optional<String> propertyType = SpdxJsonLDContext.getInstance().getType(property);
			if (list) {
				store.addValueToCollection(documentUri, id, 
						MultiFormatStore.collectionPropertyNameToPropertyName(property), 
						toStoredObject(documentUri, id, 
								property, value, propertyType, spdxIdProperties, list));
			} else {
				store.setValue(documentUri, id, property, toStoredObject(documentUri, id, 
						property, value, propertyType, spdxIdProperties, list));
			}
		}
	}
	/**
	 * Convert the value to the appropriate type to be stored
	 * @param documentUri document URI
	 * @param id ID of the object to store the value
	 * @param property property name
	 * @param value JSON node containing the value
	 * @param propertyType type of property
	 * @param spdxIdProperties Properties which contain an SPDX ID which needs to be replaced
	 * @param list true if this property is a list type
	 * @return the object to be stored
	 * @throws InvalidSPDXAnalysisException
	 */
	private Object toStoredObject(String documentUri, String id, String property, JsonNode value, 
			Optional<String> propertyType, Map<String, String> spdxIdProperties, boolean list) throws InvalidSPDXAnalysisException {
		switch (value.getNodeType()) {
		case ARRAY:
			throw new InvalidSPDXAnalysisException("Can not convert a JSON array to a stored object");
		case BOOLEAN:
			if (propertyType.isPresent()) {
				Class<? extends Object> toStoreClass = SpdxJsonLDContext.XMLSCHEMA_TYPE_TO_JAVA_CLASS.get(propertyType.get());
				if (Objects.isNull(toStoreClass)) {
					// assume it is a boolean type
					return value.asBoolean();
				} else if (String.class.equals(toStoreClass)) {
					return Boolean.toString(value.asBoolean());
				} else if (Boolean.class.equals(toStoreClass)) {
					return value.asBoolean();
				} else {
					throw new InvalidSPDXAnalysisException("Can not convert a JSON BOOLEAN to a "+toStoreClass.toString());
				}
			} else {
				return value.asBoolean();
			}
		case NULL: throw new InvalidSPDXAnalysisException("Can not convert a JSON NULL to a stored object");
		case NUMBER: {
			if (propertyType.isPresent()) {
				Class<? extends Object> toStoreClass = SpdxJsonLDContext.XMLSCHEMA_TYPE_TO_JAVA_CLASS.get(propertyType.get());
				if (Objects.isNull(toStoreClass)) {
					// assume it is a integer type
					return value.asInt();
				} else if (String.class.equals(toStoreClass)) {
					return Double.toString(value.asDouble());
				} else if (Integer.class.equals(toStoreClass)) {
					return value.asInt();
				} else {
					throw new InvalidSPDXAnalysisException("Can not convert a JSON NUMBER to a "+toStoreClass.toString());
				}
			} else {
				return value.asInt();
			}
		}
		case OBJECT: {
			if (!propertyType.isPresent()) {
				throw new InvalidSPDXAnalysisException("Unknown type for property " + property);
			}
			if (SpdxConstantsCompatV2.CLASS_SINGLE_POINTER.equals(propertyType.get())) {
				// need to determine whether a byte or line pointer type
				// A bit of Duck Typing is in order
				if (Objects.nonNull(value.get(SpdxConstantsCompatV2.PROP_POINTER_OFFSET.getName()))) {
					propertyType = Optional.of(SpdxConstantsCompatV2.CLASS_POINTER_BYTE_OFFSET_POINTER);
				} else if (Objects.nonNull(value.get(SpdxConstantsCompatV2.PROP_POINTER_LINE_NUMBER.getName()))) {
					propertyType = Optional.of(SpdxConstantsCompatV2.CLASS_POINTER_LINE_CHAR_POINTER);
				} else {
					throw new InvalidSPDXAnalysisException("Can not determine type for snippet pointer");
				}
			}
			String objectId = findObjectIdInJsonObject(documentUri, value);
			store.create(documentUri, objectId, propertyType.get());
			restoreObjectPropertyValues(documentUri, objectId, value, spdxIdProperties);
			return CompatibleModelStoreWrapper.typedValueFromDocUri(documentUri, objectId, store, propertyType.get());
		}
		case STRING:
			return getStringPropertyValueForJsonNode(documentUri, id, property, value, 
					propertyType, spdxIdProperties, list);
		case BINARY:
		case MISSING:
		case POJO:
		default: throw new InvalidSPDXAnalysisException("Unsupported JSON node type: "+value.toString());
		}
	}
	
	/**
	 * @param spdxIdProperties Properties which contain an SPDX ID which needs to be replaced
	 * 
	 * @throws InvalidSPDXAnalysisException
	 */
	/**
	 * Gets the property value for a string JsonNode
	 * @param documentUri document URI
	 * @param id ID of the object to store the value
	 * @param property property name
	 * @param value JSON node containing the value
	 * @param propertyType
	 * @param list true if this property is a list type
	 * @return the appropriate object to store
	 * @throws InvalidSPDXAnalysisException
	 */
	private Object getStringPropertyValueForJsonNode(String documentUri, String id, String property, JsonNode value,
			Optional<String> propertyType, Map<String, String> spdxIdProperties, boolean list) throws InvalidSPDXAnalysisException {
		Class<?> clazz = null;
		if (propertyType.isPresent()) {
			// check for SPDX model types
			clazz = SpdxModelFactoryCompatV2.SPDX_TYPE_TO_CLASS_V2.get(propertyType.get());
			if (Objects.isNull(clazz)) {
				// check for primitive types
				clazz = SpdxJsonLDContext.XMLSCHEMA_TYPE_TO_JAVA_CLASS.get(propertyType.get());
			}
		}
		if (Objects.isNull(clazz)) {
			// Just return the string value
			return value.asText();
		} else {
			// check for SPDX model classes
			if (AnyLicenseInfo.class.isAssignableFrom(clazz)) {
				// convert license expressions to their model object form
				AnyLicenseInfo parsedLicense = LicenseInfoFactory.parseSPDXLicenseStringCompatV2(value.asText(), store, documentUri, null);
				return ModelObjectHelper.modelObjectToStoredObject(parsedLicense, store, null, null);			
			} else if (SpdxDocument.class.isAssignableFrom(clazz)) {
				// Convert any IndividualUriValue values
				final String uriValue = value.asText();
				return new IndividualUriValue() {

					@Override
					public String getIndividualURI() {
						return uriValue;
					}
					
				};
			} else if (ReferenceType.class.isAssignableFrom(clazz)) {
                String referenceTypeValue = value.asText();
                try {
                    // First, try to find a listed type
                    ReferenceType referenceType = ListedReferenceTypes.getListedReferenceTypes()
                            .getListedReferenceTypeByName(referenceTypeValue);
                    if (Objects.nonNull(referenceType)) {
                        return referenceType;
                    }
                } catch (InvalidSPDXAnalysisException e) {
                    // Ignore - fall through to using the IndividualUri
                }
                final String uriValue = referenceTypeValue;
                // Check for listed reference types
                return new IndividualUriValue() {

                    @Override
                    public String getIndividualURI() {
                        return uriValue;
                    }
                    
                };
            } else if (SpdxElement.class.isAssignableFrom(clazz)) {
				// store the ID and save it in the spdxIdProperties to replace with the actual class later
				// once everything is restored
				if (list) {
					spdxIdProperties.put(id, MultiFormatStore.collectionPropertyNameToPropertyName(property));
				} else {
					spdxIdProperties.put(id, property);
				}
				return value.asText();
			} else if (clazz.isEnum()) {
				for (Object enumConst:clazz.getEnumConstants()) {
					if (enumConst instanceof IndividualUriValue && value.asText().replaceAll("-","_").equals(enumConst.toString())) {
						return new SimpleUriValue((IndividualUriValue)enumConst);
					}
				}
				throw new InvalidSPDXAnalysisException("Could not find enum constants for "+value.asText()+" property "+property);
			} else if (String.class.equals(clazz)) {
				return value.asText();
			} else if (Boolean.class.equals(clazz)) {
				try {
					return Boolean.parseBoolean(value.asText());
				} catch (Exception ex) {
					throw new InvalidSPDXAnalysisException("Unable to convert "+value.asText()+" to boolean for property "+property);
				}
			} else if (Integer.class.equals(clazz)) {
				try {
					return Integer.parseInt(value.asText());
				} catch (Exception ex) {
					throw new InvalidSPDXAnalysisException("Unable to convert "+value.asText()+" to integer for property "+property);
				}
			}	else {
				throw new InvalidSPDXAnalysisException("Unknown type: "+propertyType.get()+" for property "+property);
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
		JsonNode retval = jsonObject.get(SpdxConstantsCompatV2.SPDX_IDENTIFIER);
		if (Objects.isNull(retval) || !retval.isTextual()) {
			retval = jsonObject.get(SpdxConstantsCompatV2.PROP_LICENSE_ID.getName());
		}
		if (Objects.isNull(retval) || !retval.isTextual()) {
			retval = jsonObject.get(SpdxConstantsCompatV2.PROP_LICENSE_EXCEPTION_ID.getName());
		}
		if (Objects.isNull(retval) || !retval.isTextual()) {
			retval = jsonObject.get(SpdxConstantsCompatV2.EXTERNAL_DOCUMENT_REF_IDENTIFIER);
		}
		if (Objects.isNull(retval) || !retval.isTextual()) {
			return store.getNextId(IdType.Anonymous, documentUri);
		} else {
			return retval.asText();
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
			if (spdxId.equals(SpdxConstantsCompatV2.NONE_VALUE)) {
				return new IndividualUriValue() {

					@Override
					public String getIndividualURI() {
						return SpdxConstantsCompatV2.URI_VALUE_NONE;
					}
					
				};
			} else if (spdxId.equals(SpdxConstantsCompatV2.NOASSERTION_VALUE)) {
				return new IndividualUriValue() {

					@Override
					public String getIndividualURI() {
						return SpdxConstantsCompatV2.URI_VALUE_NOASSERTION;
					}
					
				};
			} else if (spdxId.startsWith("DocumentRef-")) {
				final IModelStore modelStore = store;
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
}

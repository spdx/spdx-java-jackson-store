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

import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.library.model.ExternalSpdxElement;
import org.spdx.library.model.IndividualUriValue;
import org.spdx.library.model.ReferenceType;
import org.spdx.library.model.SimpleUriValue;
import org.spdx.library.model.SpdxDocument;
import org.spdx.library.model.SpdxElement;
import org.spdx.library.model.SpdxModelFactory;
import org.spdx.library.model.TypedValue;
import org.spdx.library.model.enumerations.RelationshipType;
import org.spdx.library.model.license.AnyLicenseInfo;
import org.spdx.library.model.license.LicenseInfoFactory;
import org.spdx.storage.IModelStore;
import org.spdx.storage.IModelStore.IModelStoreLock;
import org.spdx.storage.IModelStore.IdType;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Converts a Jackson node for a document to a stored document in a model store
 * 
 * @author Gary O'Neall
 *
 */
public class JacksonDeSerializer {
	
	/**
	 * Properties that should not be restored as part of the deserialization
	 */
	static final Set<String> SKIPPED_PROPERTIES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(new String[] {
			SpdxConstants.PROP_DOCUMENT_DESCRIBES, SpdxConstants.PROP_DOCUMENT_PACKAGES, SpdxConstants.PROP_DOCUMENT_FILES,
			SpdxConstants.PROP_DOCUMENT_SNIPPETS, SpdxConstants.SPDX_IDENTIFIER, SpdxConstants.PROP_DOCUMENT_RELATIONSHIPS
	})));

	private IModelStore store;

	/**
	 * @param store store to store any documents in
	 */
	public JacksonDeSerializer(IModelStore store) {
		Objects.requireNonNull(store, "Model store can not be null");
		this.store = store;
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
			store.create(documentNamespace, SpdxConstants.SPDX_DOCUMENT_ID, SpdxConstants.CLASS_SPDX_DOCUMENT);
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
			store.create(documentUri, id, type);
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
			String relationshipId = store.getNextId(IdType.Anonymous, documentNamespace);
			store.create(documentNamespace, relationshipId, SpdxConstants.CLASS_RELATIONSHIP);
			store.setValue(documentNamespace, relationshipId, SpdxConstants.PROP_RELATIONSHIP_TYPE, relationshipType);
			store.setValue(documentNamespace, relationshipId, SpdxConstants.PROP_RELATED_SPDX_ELEMENT, relatedElement);
			store.addValueToCollection(documentNamespace, element.getId(), SpdxConstants.PROP_RELATIONSHIP, 
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
				String individualPropertyName = MultiFormatStore.collectionPropertyNameToPropertyName(property);
				setPropertyValueForJsonNode(documentUri, id, individualPropertyName, iter.next(), spdxIdProperties, true);
			}
		}; break;
		case BOOLEAN: {
			if (list) {
				store.addValueToCollection(documentUri, id, property, value.asBoolean());
			} else {
				store.setValue(documentUri, id, property, value.asBoolean()); 
			}
		} break;
		case NULL: break; // ignore
		case NUMBER: {
			if (list) {
				store.addValueToCollection(documentUri, id, property, value.asInt());
			} else {
				store.setValue(documentUri, id, property, value.asInt()); 
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
			store.create(documentUri, objectId, propertyType.get());
			restoreObjectPropertyValues(documentUri, objectId, value, spdxIdProperties);
			if (list) {
				store.addValueToCollection(documentUri, id, property, new TypedValue(objectId, propertyType.get()));
			} else {
				store.setValue(documentUri, id, property, new TypedValue(objectId, propertyType.get()));
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
				AnyLicenseInfo parsedLicense = LicenseInfoFactory.parseSPDXLicenseString(value.asText(), store, documentUri, null);
				if (list) {
					store.addValueToCollection(documentUri, id, property, new TypedValue(parsedLicense.getId(), parsedLicense.getType()));
				} else {
					store.setValue(documentUri, id, property, new TypedValue(parsedLicense.getId(), parsedLicense.getType()));
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
					store.addValueToCollection(documentUri, id, property, individualUriValue);
				} else {
					store.setValue(documentUri, id, property, individualUriValue);
				}
			} else if (SpdxElement.class.isAssignableFrom(clazz)) {
				if (list) {
					store.addValueToCollection(documentUri, id, property, value.asText());
				} else {
					store.setValue(documentUri, id, property, value.asText());
				}
				spdxIdProperties.put(id, property);
			} else if (clazz.isEnum()) {					
				for (Object enumConst:clazz.getEnumConstants()) {
					if (enumConst instanceof IndividualUriValue && value.asText().equals(enumConst.toString())) {
						IndividualUriValue iuv = new SimpleUriValue((IndividualUriValue)enumConst);
						if (list) {
							store.addValueToCollection(documentUri, id, property, iuv);
						} else {
							store.setValue(documentUri, id, property, iuv);
						}
					}
				}
			} else {
				throw new InvalidSPDXAnalysisException("Unknown type: "+propertyType.get()+" for property "+property);
			}
		} else {
			if (list) {
				store.addValueToCollection(documentUri, id, property, value.asText()); 
			} else {
				store.setValue(documentUri, id, property, value.asText()); 
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
			if (spdxId.startsWith("DocumentRef-")) {
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

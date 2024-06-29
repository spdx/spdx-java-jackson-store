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
import java.util.Collections;
import java.util.Comparator;
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
import org.spdx.core.CoreModelObject;
import org.spdx.core.IndividualUriValue;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.core.ModelRegistry;
import org.spdx.core.SpdxInvalidTypeException;
import org.spdx.core.TypedValue;
import org.spdx.jacksonstore.MultiFormatStore.Format;
import org.spdx.jacksonstore.MultiFormatStore.Verbose;
import org.spdx.library.model.v2.ExternalDocumentRef;
import org.spdx.library.model.v2.ExternalSpdxElement;
import org.spdx.library.model.v2.SpdxConstantsCompatV2;
import org.spdx.library.model.v2.SpdxElement;
import org.spdx.library.model.v2.SpdxModelFactoryCompatV2;
import org.spdx.library.model.v2.enumerations.Purpose;
import org.spdx.library.model.v2.enumerations.RelationshipType;
import org.spdx.library.model.v2.enumerations.SpdxEnumFactoryCompatV2;
import org.spdx.library.model.v2.license.AnyLicenseInfo;
import org.spdx.library.model.v2.license.SimpleLicensingInfo;
import org.spdx.storage.CompatibleModelStoreWrapper;
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
	
	static final Comparator<JsonNode> NODE_COMPARATOR = new Comparator<JsonNode>() {

		@Override
		public int compare(JsonNode arg0, JsonNode arg1) {
			if (Objects.isNull(arg0)) {
				return Objects.isNull(arg1) ? 0 : 1;
			}
			if (Objects.isNull(arg1)) {
				return -1;
			}
			if (arg0.isTextual()) {
				return arg1.isTextual() ? arg0.asText().compareTo(arg1.asText()) : 1;
			} else if (arg0.isObject()) {
				return arg1.isObject() ? compareObject(arg0, arg1) : 1;
			} else if (arg0.isArray()) {
				if (!arg1.isArray()) {
					return 1;
				}
				if (arg0.size() > arg1.size()) {
					return 1;
				} else if (arg0.size() < arg1.size()) {
					return -1;
				} else {
					List<JsonNode> list0 = new ArrayList<>();
					arg0.spliterator().forEachRemaining((node) -> list0.add(node));
					list0.sort(NODE_COMPARATOR);
					List<JsonNode> list1 = new ArrayList<>();
					arg1.spliterator().forEachRemaining((node) -> list1.add(node));
					list1.sort(NODE_COMPARATOR);
					for (int i = 0; i < list0.size(); i++) {
						int retval = compare(list0.get(i), list1.get(i));
						if (retval != 0) {
							return retval;
						}
					}
					return 0;
				}
			} else {
				return Integer.compare(arg0.hashCode(), arg1.hashCode());
			}
		}

		private int compareObject(JsonNode arg0, JsonNode arg1) {
			if (!arg1.isObject()) {
				return 1;
			}
			JsonNode spdxid0 = arg0.get(SpdxConstantsCompatV2.SPDX_IDENTIFIER);
			if (Objects.nonNull(spdxid0) && spdxid0.isTextual()) {
				JsonNode spdxid1 = arg1.get(SpdxConstantsCompatV2.SPDX_IDENTIFIER);
				return Objects.nonNull(spdxid1) && spdxid1.isTextual() ? spdxid0.asText().compareTo(spdxid1.asText()) : 1;
			}
			// look for special cases where we don't want to sort in the order of properties
			JsonNode licenseId0 = arg0.get(SpdxConstantsCompatV2.PROP_LICENSE_ID.getName());
			if (Objects.nonNull(licenseId0) && licenseId0.isTextual()) {
				return compareExtractedLicense(licenseId0.asText(), arg1);
			}
			JsonNode spdxDocument0 = arg0.get(SpdxConstantsCompatV2.PROP_EXTERNAL_SPDX_DOCUMENT.getName());
			JsonNode externalDocId0 = arg0.get(SpdxConstantsCompatV2.PROP_EXTERNAL_DOCUMENT_ID.getName());
			if (Objects.nonNull(spdxDocument0) && Objects.nonNull(externalDocId0) &&
					spdxDocument0.isTextual() && externalDocId0.isTextual()) {
				return compareExternalDocumentRef(spdxDocument0.asText(), externalDocId0.asText(), arg1);
			}
			JsonNode refCategory0 = arg0.get(SpdxConstantsCompatV2.PROP_REFERENCE_CATEGORY.getName());
			JsonNode refType0 = arg0.get(SpdxConstantsCompatV2.PROP_REFERENCE_TYPE.getName());
			JsonNode refLocator0 = arg0.get(SpdxConstantsCompatV2.PROP_REFERENCE_LOCATOR.getName());
			if (Objects.nonNull(refCategory0) && Objects.nonNull(refType0) && Objects.nonNull(refLocator0) &&
				refCategory0.isTextual() && refType0.isTextual() && refLocator0.isTextual()) {
					return compareExternalRef(refCategory0.asText(), refType0.asText(), refLocator0.asText(), arg1);
			}
			List<String> fieldNames = new ArrayList<>();
			arg0.fieldNames().forEachRemaining((String field) -> fieldNames.add(field));
			Collections.sort(fieldNames);
			int retval = 0;
			for (String fieldName:fieldNames) {
				JsonNode value0 = arg0.get(fieldName);
				JsonNode value1 = arg1.get(fieldName);
				retval = compare(value0, value1);
				if (retval != 0) {
					return retval;
				}
			}
			return retval;
		}

		private int compareExternalRef(String refCategory, String refType,
				String refLocator, JsonNode compare) {
			JsonNode compRefCategory = compare.get(SpdxConstantsCompatV2.PROP_REFERENCE_CATEGORY.getName());
			JsonNode compRefType = compare.get(SpdxConstantsCompatV2.PROP_REFERENCE_TYPE.getName());
			JsonNode compRefLocator = compare.get(SpdxConstantsCompatV2.PROP_REFERENCE_LOCATOR.getName());
			if (Objects.isNull(compRefCategory) || Objects.isNull(compRefType) || Objects.isNull(compRefLocator) ||
					!compRefCategory.isTextual() || !compRefType.isTextual() || !compRefLocator.isTextual()) {
				return 1;
			}
			int retval = refCategory.compareTo(compRefCategory.asText());
			if (retval != 0) {
				return retval;
			}
			retval = refType.compareTo(compRefType.asText());
			if (retval != 0) {
				return retval;
			}
			return refLocator.compareTo(compRefLocator.asText());
		}

		private int compareExternalDocumentRef(String document, String externalDocId,
				JsonNode compare) {
			JsonNode compDocument = compare.get(SpdxConstantsCompatV2.PROP_EXTERNAL_SPDX_DOCUMENT.getName());
			JsonNode compExternalDocId = compare.get(SpdxConstantsCompatV2.PROP_EXTERNAL_DOCUMENT_ID.getName());
			if (Objects.isNull(compDocument) || Objects.isNull(compExternalDocId) ||
					!compDocument.isTextual() || !compExternalDocId.isTextual()) {
				return 1;
			}
			int retval = document.compareTo(compDocument.asText());
			if (retval != 0) {
				return retval;
			}
			return externalDocId.compareTo(compExternalDocId.asText());
		}

		private int compareExtractedLicense(String licenseId, JsonNode compare) {
			JsonNode compLicenseId = compare.get(SpdxConstantsCompatV2.PROP_LICENSE_ID.getName());
			if (Objects.isNull(compLicenseId) || !compLicenseId.isTextual()) {
				return 1;
			} else {
				return licenseId.compareTo(compLicenseId.asText());
			}
		}
		
	};

	private ObjectMapper mapper;
	private CompatibleModelStoreWrapper store;
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
		this.store = new CompatibleModelStoreWrapper(store);
		this.format = format;
		this.verbose = verbose;
	}
	
	/**
	 * @param documentUris list of document uris
	 * @return JSON array of all documents which have the document Uris
	 * @throws InvalidSPDXAnalysisException
	 */
	public ArrayNode docsToJsonNode(List<String> documentUris) throws InvalidSPDXAnalysisException {
		ArrayNode retval = mapper.createArrayNode();
		for (String documentUri:documentUris) {
			retval.add(docToJsonNode(documentUri));
		}
		return retval;
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
			TypedValue document = CompatibleModelStoreWrapper.typedValueFromDocUri(documentUri, 
					SpdxConstantsCompatV2.SPDX_DOCUMENT_ID, false, SpdxConstantsCompatV2.CLASS_SPDX_DOCUMENT);
			ArrayNode relationships = mapper.createArrayNode();
			ObjectNode doc = typedValueToObjectNode(documentUri, document, relationships);
			doc.put(SpdxConstantsCompatV2.PROP_DOCUMENT_NAMESPACE.getName(), documentUri);
			ArrayNode packages = getDocElements(documentUri, SpdxConstantsCompatV2.CLASS_SPDX_PACKAGE, relationships);
			if (packages.size() > 0) {
				doc.set(SpdxConstantsCompatV2.PROP_DOCUMENT_PACKAGES.getName(), packages);
			}
			ArrayNode files = getDocElements(documentUri, SpdxConstantsCompatV2.CLASS_SPDX_FILE, relationships);
			if (files.size() > 0) {
				doc.set(SpdxConstantsCompatV2.PROP_DOCUMENT_FILES.getName(), files);
			}
			ArrayNode snippets = getDocElements(documentUri, SpdxConstantsCompatV2.CLASS_SPDX_SNIPPET, relationships);
			if (snippets.size() > 0) {
				doc.set(SpdxConstantsCompatV2.PROP_DOCUMENT_SNIPPETS.getName(), snippets);
			}
			//Remove duplicate relationships
			Map<String, Map<String, Set<String>>> iDRelTypeRelatediDMap = new HashMap<>();
			Iterator<JsonNode> relIter = relationships.elements();
			ArrayNode deDupedRelationships = new ArrayNode(JsonNodeFactory.instance);
			while (relIter.hasNext()) {
				JsonNode relationship = relIter.next();
				String id = relationship.get(SpdxConstantsCompatV2.PROP_SPDX_ELEMENTID.getName()).asText();
				String relType = relationship.get(SpdxConstantsCompatV2.PROP_RELATIONSHIP_TYPE.getName()).asText();
				String relatedID = relationship.get(SpdxConstantsCompatV2.PROP_RELATED_SPDX_ELEMENT.getName()).asText();
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
			
			
			doc.set(SpdxConstantsCompatV2.PROP_DOCUMENT_RELATIONSHIPS.getName(), deDupedRelationships);
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
		List<String> docPropNames = new ArrayList<String>(store.getPropertyValueNames(storedItem.getObjectUri()));
		docPropNames.sort(new PropertyComparator(storedItem.getType()));
		Class<?> clazz = SpdxModelFactoryCompatV2.SPDX_TYPE_TO_CLASS_V2.get(storedItem.getType());
		IdType idType = store.getIdType(storedItem.getObjectUri());
		String id = CompatibleModelStoreWrapper.objectUriToId(store, storedItem.getObjectUri(), documentUri);
		if (SpdxElement.class.isAssignableFrom(clazz)) {
			if (IdType.SpdxId.equals(idType)) {
				retval.put(SpdxConstantsCompatV2.SPDX_IDENTIFIER, CompatibleModelStoreWrapper.objectUriToId(store, storedItem.getObjectUri(), documentUri));
			} else if (!IdType.Anonymous.equals(idType)) {
				logger.error("Invalid ID "+storedItem.getObjectUri()+".  Must be an SPDX Identifier or Anonymous");
				throw new InvalidSPDXAnalysisException("Invalid ID "+storedItem.getObjectUri()+".  Must be an SPDX Identifier or Anonymous");
			}
		} else if (ExternalDocumentRef.class.isAssignableFrom(clazz)) {
			retval.put(SpdxConstantsCompatV2.EXTERNAL_DOCUMENT_REF_IDENTIFIER, CompatibleModelStoreWrapper.objectUriToId(store, storedItem.getObjectUri(), documentUri));
		} else if (SimpleLicensingInfo.class.isAssignableFrom(clazz)) {
			retval.put(SpdxConstantsCompatV2.PROP_LICENSE_ID.getName(), id);
		}
		for (String propertyName:docPropNames) {
			if (SpdxConstantsCompatV2.PROP_RELATIONSHIP.getName().equals(propertyName)) {
				addJsonRelationships(documentUri, storedItem, retval, relationships, hasFileIds, documentDescribesIds);
			} else if (SpdxConstantsCompatV2.PROP_SPDX_EXTRACTED_LICENSES.getName().equals(propertyName)) {
				retval.set(MultiFormatStore.propertyNameToCollectionPropertyName(propertyName), 
						toExtractedLicensesArrayNode(documentUri, id, propertyName, relationships));
			} else if (store.isCollectionProperty(documentUri, id, propertyName)) {
				Iterator<Object> propertyValues = store.listValues(documentUri, id, propertyName);
				ArrayNode valuesArray;
				if (SpdxConstantsCompatV2.PROP_PACKAGE_FILE.getName().equals(propertyName) && hasFileIds.size() > 0) {
					// Need to filter out existing hasFileIds
					List<Object> hasFilesToAdd = new ArrayList<>();
					while (propertyValues.hasNext()) {
						Object fileToAdd = propertyValues.next();
						if (fileToAdd instanceof TypedValue) {
							String fileToAddId = CompatibleModelStoreWrapper.objectUriToId(store, 
									((TypedValue)fileToAdd).getObjectUri(), documentUri);
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
					
				} else if (SpdxConstantsCompatV2.PROP_DOCUMENT_DESCRIBES.getName().equals(propertyName) && documentDescribesIds.size() > 0) {
					// Need to filter out existing documentDescribes
					List<Object> describesToAdd = new ArrayList<>();
					while (propertyValues.hasNext()) {
						Object describedElementToAdd = propertyValues.next();
						if (describedElementToAdd instanceof TypedValue) {
							String describedElementToAddId = CompatibleModelStoreWrapper.objectUriToId(store, 
									((TypedValue)describedElementToAdd).getObjectUri(), documentUri);
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
				Optional<Object> value = store.getValue(documentUri, id, propertyName);
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
		List<JsonNode> retval = new ArrayList<>();
		Iterator<Object> extractedLicenses = store.listValues(documentUri, id, propertyName);
		while (extractedLicenses.hasNext()) {
			Object extractedLicense = extractedLicenses.next();
			if (!(extractedLicense instanceof TypedValue) || 
					(!SpdxConstantsCompatV2.CLASS_SPDX_EXTRACTED_LICENSING_INFO.equals(((TypedValue)extractedLicense).getType()))) {
				throw new SpdxInvalidTypeException("Extracted License Infos not of type "+SpdxConstantsCompatV2.CLASS_SPDX_EXTRACTED_LICENSING_INFO);
			}
			retval.add(typedValueToObjectNode(documentUri, (TypedValue)extractedLicense, relationships));
		}
		retval.sort(NODE_COMPARATOR);
		return mapper.createArrayNode().addAll(retval);
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
		Iterator<Object> valueList = store.listValues(documentUri, CompatibleModelStoreWrapper.objectUriToId(store, 
				element.getObjectUri(), documentUri), 
				SpdxConstantsCompatV2.PROP_RELATIONSHIP);
		while (valueList.hasNext()) {
			Object value = valueList.next();
			if (!(value instanceof TypedValue)) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+value.getClass().toString());
			}
			TypedValue tvValue = (TypedValue)value;
			String tvId = CompatibleModelStoreWrapper.objectUriToId(store, tvValue.getObjectUri(), documentUri);
			if (!SpdxConstantsCompatV2.CLASS_RELATIONSHIP.equals(tvValue.getType())) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+tvValue.getType());
			}
			Optional<Object> relatedSpdxElement = store.getValue(documentUri, tvId, SpdxConstantsCompatV2.PROP_RELATED_SPDX_ELEMENT); 
			if (!relatedSpdxElement.isPresent()) {
				logger.warn("Missing related SPDX element for a relationship for "+element.getObjectUri()+".  Skipping the serialization of this relationship.");
				continue;
			}
			Optional<Object> relationshipComment = store.getValue(documentUri, tvId, SpdxConstantsCompatV2.RDFS_PROP_COMMENT);
			String relatedElementId;
			if (relatedSpdxElement.get() instanceof TypedValue) {
				relatedElementId = CompatibleModelStoreWrapper.objectUriToId(store, ((TypedValue)relatedSpdxElement.get()).getObjectUri(), documentUri);
			} else if (relatedSpdxElement.get() instanceof IndividualUriValue) {
				String externalUri = ((IndividualUriValue)relatedSpdxElement.get()).getIndividualURI();
				if (SpdxConstantsCompatV2.URI_VALUE_NONE.equals(externalUri)) {
					relatedElementId = SpdxConstantsCompatV2.NONE_VALUE;
				} else if (SpdxConstantsCompatV2.URI_VALUE_NOASSERTION.equals(externalUri)) {
					relatedElementId = SpdxConstantsCompatV2.NOASSERTION_VALUE;
				} else if (SpdxConstantsCompatV2.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(externalUri).matches()) {
					// external SPDX element
					relatedElementId = ExternalSpdxElement.uriToExternalSpdxElementReference(externalUri, store, documentUri, null, CompatibleModelStoreWrapper.LATEST_SPDX_2X_VERSION);
				} else {
					throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement, SpdxNoneElement, SpdxNoAssertionElement or external SPDX element type.  URI does not match pattern for external element: "+externalUri);
				}
			}  else {
				throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement or external SPDX element type.  Found type "+relatedSpdxElement.get().getClass().toString());
			}
			
			Optional<Object> relationshipType = store.getValue(documentUri, tvId, SpdxConstantsCompatV2.PROP_RELATIONSHIP_TYPE);
			if (!relationshipType.isPresent()) {
				logger.warn("Missing type for a relationship for "+element.getObjectUri()+".  Skipping the serialization of this relationship.");
				continue;
			}
			if (!(relationshipType.get() instanceof IndividualUriValue)) {
				throw new SpdxInvalidTypeException("Expected RelationshipType type for relationshipType property.  Unexpected type "+relatedSpdxElement.get().getClass().toString());
			}
			String relationshipTypeStr = individualUriToString(documentUri, ((IndividualUriValue)relationshipType.get()).getIndividualURI());
			ObjectNode relationship = mapper.createObjectNode();
			relationship.put(SpdxConstantsCompatV2.PROP_SPDX_ELEMENTID.getName(), CompatibleModelStoreWrapper.objectUriToId(store, 
					element.getObjectUri(), documentUri));
			relationship.put(SpdxConstantsCompatV2.PROP_RELATIONSHIP_TYPE.getName(), relationshipTypeStr);
			relationship.put(SpdxConstantsCompatV2.PROP_RELATED_SPDX_ELEMENT.getName(), relatedElementId);
			if (relationshipComment.isPresent()) {
				relationship.put(SpdxConstantsCompatV2.RDFS_PROP_COMMENT.getName(), (String)relationshipComment.get());
			}
			relationships.add(relationship);
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
		ArrayNode unsorted = mapper.createArrayNode();
		while (valueList.hasNext()) {
			Object value = valueList.next();
			addValueToArrayNode(unsorted, documentUri, value, relationships);
		}
		List<JsonNode> nodeList = new ArrayList<>();
		unsorted.spliterator().forEachRemaining((node) -> nodeList.add(node));
		Collections.sort(nodeList, NODE_COMPARATOR);
		return mapper.createArrayNode().addAll(unsorted);
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
			String tvId = CompatibleModelStoreWrapper.objectUriToId(store, tvStoredValue.getObjectUri(), documentUri);
			Class<?> clazz = SpdxModelFactoryCompatV2.SPDX_TYPE_TO_CLASS_V2.get(tvStoredValue.getType());
			if (AnyLicenseInfo.class.isAssignableFrom(clazz) && 
					(Verbose.STANDARD.equals(verbose) || Verbose.COMPACT.equals(verbose))) {
				CoreModelObject inflated = ModelRegistry.getModelRegistry().inflateModelObject(store, tvStoredValue.getObjectUri(), tvStoredValue.getType(), 
						null, tvStoredValue.getSpecVersion(), false, documentUri + "#");
				return inflated.toString();
			} else if (SpdxElement.class.isAssignableFrom(clazz) &&
					Verbose.COMPACT.equals(verbose) &&
					!IModelStore.IdType.Anonymous.equals(store.getIdType(tvId))) {
				return tvId;
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
		Object enumval = SpdxEnumFactoryCompatV2.uriToEnum.get(uri);
		if (Objects.nonNull(enumval)) {
			if (enumval instanceof RelationshipType || enumval instanceof Purpose) {
				return enumval.toString();
			} else {
				return enumval.toString().replaceAll("_", "-");
			}
		} else if (SpdxConstantsCompatV2.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(uri).matches()) {
			return ExternalSpdxElement.uriToExternalSpdxElementReference(uri, store, documentUri, null, 
					CompatibleModelStoreWrapper.LATEST_SPDX_2X_VERSION);
		} else if (SpdxConstantsCompatV2.URI_VALUE_NONE.equals(uri)) {
			return SpdxConstantsCompatV2.NONE_VALUE;
		} else if (SpdxConstantsCompatV2.URI_VALUE_NOASSERTION.equals(uri)) {
			return SpdxConstantsCompatV2.NOASSERTION_VALUE;
		} else if (uri.startsWith(SpdxConstantsCompatV2.SPDX_LISTED_REFERENCE_TYPES_PREFIX)) {
		    return uri.substring(SpdxConstantsCompatV2.SPDX_LISTED_REFERENCE_TYPES_PREFIX.length());
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

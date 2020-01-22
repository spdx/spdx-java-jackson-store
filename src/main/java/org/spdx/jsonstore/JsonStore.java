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
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.library.model.ExternalSpdxElement;
import org.spdx.library.model.IndividualUriValue;
import org.spdx.library.model.SpdxInvalidTypeException;
import org.spdx.library.model.SpdxModelFactory;
import org.spdx.library.model.TypedValue;
import org.spdx.library.model.enumerations.SpdxEnumFactory;
import org.spdx.library.model.license.AnyLicenseInfo;
import org.spdx.storage.ISerializableModelStore;
import org.spdx.storage.simple.InMemSpdxStore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;

/**
 * @author Gary O'Neall
 *
 */
public class JsonStore extends InMemSpdxStore implements ISerializableModelStore {
	
	static final Logger logger = LoggerFactory.getLogger(JsonStore.class);

	Gson gson = new GsonBuilder().setPrettyPrinting().create();
	/**
	 * Construct an empty Json Store
	 */
	public JsonStore() {
		super();
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#serialize(java.lang.String, java.io.OutputStream)
	 */
	public void serialize(String documentUri, OutputStream stream) throws InvalidSPDXAnalysisException, IOException {
		// Start with the document
		TypedValue document = new TypedValue(SpdxConstants.SPDX_DOCUMENT_ID, SpdxConstants.CLASS_SPDX_DOCUMENT);
		JsonArray relationships = new JsonArray();
		JsonObject jsonDoc = toJsonObject(documentUri, document, relationships);
		JsonArray documentDescribes = getDocumentDescribes(relationships);
		jsonDoc.add(SpdxConstants.PROP_DOCUMENT_DESCRIBES, documentDescribes);
		JsonArray packages = getDocElements(documentUri, SpdxConstants.CLASS_SPDX_PACKAGE);
		if (packages.size() > 0) {
			jsonDoc.add(SpdxConstants.PROP_DOCUMENT_PACKAGES, packages);
		}
		JsonArray files = getDocElements(documentUri, SpdxConstants.CLASS_SPDX_FILE);
		if (files.size() > 0) {
			jsonDoc.add(SpdxConstants.PROP_DOCUMENT_FILES, files);
		}		
		JsonArray snippets = getDocElements(documentUri, SpdxConstants.CLASS_SPDX_SNIPPET);
		if (snippets.size() > 0) {
			jsonDoc.add(SpdxConstants.PROP_DOCUMENT_SNIPPETS, snippets);
		}
		jsonDoc.add(SpdxConstants.PROP_DOCUMENT_RELATIONSHIPS, relationships);
		JsonObject output = new JsonObject();
		output.add("Document", jsonDoc);
		JsonWriter writer = new JsonWriter(new OutputStreamWriter(stream));
		writer.setIndent("  ");
		try {
			gson.toJson(output, writer);
		} finally {
			writer.close();
		}
	}

	/**
	 * @param documentUri Document namespace or Uri
	 * @param type type of document element to get (Package, File, or Snippet)
	 * @return JsonArray of document elements matching the type
	 * @throws InvalidSPDXAnalysisException
	 */
	private JsonArray getDocElements(String documentUri, String type) throws InvalidSPDXAnalysisException {
		JsonArray retval = new JsonArray();
		JsonArray relationships = new JsonArray();
		this.getAllItems(documentUri, type).forEach(tv -> {
			try {
				retval.add(toJsonObject(documentUri, tv, relationships));
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
	private JsonArray getDocumentDescribes(JsonArray relationships) {
		JsonArray documentDescribes = new JsonArray();
		for (JsonElement relationship:relationships) {
			if (relationship instanceof JsonObject) {
				JsonElement typeElement = ((JsonObject)relationship).get(SpdxConstants.PROP_RELATIONSHIP_TYPE);
				if (typeElement instanceof JsonPrimitive &&
						"DESCRIBES".equals(((JsonPrimitive)typeElement).getAsString())) {
					JsonElement elementId = ((JsonObject)relationship).get(SpdxConstants.PROP_SPDX_ELEMENTID);
					if (elementId instanceof JsonPrimitive &&
							SpdxConstants.SPDX_DOCUMENT_ID.equals(((JsonPrimitive)elementId).getAsString()));
					JsonElement relatedElement = ((JsonObject)relationship).get(SpdxConstants.PROP_RELATED_SPDX_ELEMENT);
					if (relatedElement instanceof JsonPrimitive) {
						documentDescribes.add(((JsonPrimitive)relatedElement).getAsString());
					}
				}
			}
		}
		return documentDescribes;
	}

	/**
	 * @param documentUri Document namespace or Uri
	 * @param storedValue Value to convert to a JSON serializable form
	 * @param relationships JsonArray of relationships to add any found relationships
	 * @return Json form of the item
	 * @throws InvalidSPDXAnalysisException 
	 */
	private JsonElement toJsonElement(String documentUri, Object storedValue, JsonArray relationships) throws InvalidSPDXAnalysisException {
		if (storedValue instanceof IndividualUriValue) {
			return individualUriToJsonElement(documentUri, ((IndividualUriValue)storedValue).getIndividualURI());
		} else if (storedValue instanceof TypedValue) {
			TypedValue tvStoredValue = (TypedValue)storedValue;
			Class<?> clazz = SpdxModelFactory.SPDX_TYPE_TO_CLASS.get(tvStoredValue.getType());
			if (AnyLicenseInfo.class.isAssignableFrom(clazz)) {
				AnyLicenseInfo ali = (AnyLicenseInfo)SpdxModelFactory.createModelObject(this, documentUri, 
						tvStoredValue.getId(), tvStoredValue.getType(), null);
				return new JsonPrimitive(ali.toString());
			} else {
				return toJsonObject(documentUri, (TypedValue)storedValue, relationships);
			}
		} else if (storedValue instanceof Boolean) {
			return new JsonPrimitive((Boolean)storedValue);
		} else if (storedValue instanceof String) {
			return new JsonPrimitive((String)storedValue);
		} else if (storedValue instanceof Number) {
			return new JsonPrimitive((Number)storedValue);
		} else {
			throw new SpdxInvalidTypeException("Can not serialize the JSON type for "+storedValue.getClass().toString());
		}
	}

	/**
	 * Converts a URI to a JSON string value.  The URI may represent an enumeration value or a literal value (like NONE or NOASSERTION).
	 * @param documentUri Document namespace or Uri
	 * @param uri URI value
	 * @return JSON form of the Enum or literal value represented by the URI
	 * @throws InvalidSPDXAnalysisException
	 */
	private JsonElement individualUriToJsonElement(String documentUri, String uri) throws InvalidSPDXAnalysisException {
		Object enumval = SpdxEnumFactory.uriToEnum.get(uri);
		if (Objects.nonNull(enumval)) {
			return new JsonPrimitive(enumval.toString());
		} else if (SpdxConstants.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(uri).matches()) {
			ExternalSpdxElement externalElement = ExternalSpdxElement.uriToExternalSpdxElement(uri, this, documentUri, null);
			return new JsonPrimitive(externalElement.getExternalDocumentId() + ":" + externalElement.getExternalElementId());
		} else if (SpdxConstants.URI_VALUE_NONE.equals(uri)) {
			return new JsonPrimitive(SpdxConstants.NONE_VALUE);
		} else if (SpdxConstants.URI_VALUE_NOASSERTION.equals(uri)) {
			return new JsonPrimitive(SpdxConstants.NOASSERTION_VALUE);
		} else {
			return new JsonPrimitive(uri);
		}
	}

	/**
	 * Converted a typedValue item to a JsonObject
	 * @param documentUri Document namespace or Uri
	 * @param storedItem item to convert to a JsonObject
	 * @param relationships List of any relationships found - these are added after the document
	 * @return JsonObject representation of the value
	 * @throws InvalidSPDXAnalysisException
	 */
	private JsonObject toJsonObject(String documentUri, TypedValue storedItem, JsonArray relationships) throws InvalidSPDXAnalysisException {
		List<String> docPropNames = new ArrayList<String>(this.getPropertyValueNames(documentUri, storedItem.getId()));
		docPropNames.sort(new PropertyComparator(storedItem.getType()));
		JsonObject retval = new JsonObject();
		for (String propertyName:docPropNames) {
			if (SpdxConstants.PROP_RELATIONSHIP.equals(propertyName)) {
				for (JsonElement relationship:toJsonRelationsip(documentUri, storedItem.getId(), getValueList(documentUri, storedItem.getId(), SpdxConstants.PROP_RELATIONSHIP))) {
					relationships.add(relationship);
				}
			} else if (SpdxConstants.PROP_SPDX_EXTRACTED_LICENSES.equals(propertyName)) {
				retval.add(propertyName, toExtractedLicensesJson(documentUri, storedItem.getId(), propertyName, relationships));
			} else if (this.isCollectionProperty(documentUri, storedItem.getId(), propertyName)) {
					retval.add(propertyName, toJsonArray(documentUri, this.getValueList(documentUri, storedItem.getId(), propertyName), relationships));
			} else {
				Optional<Object> value = this.getValue(documentUri, storedItem.getId(), propertyName);
				if (value.isPresent()) {
					retval.add(propertyName, toJsonElement(documentUri, value.get(), relationships));
				}
			}
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
	private Collection<? extends JsonObject> toJsonRelationsip(String documentUri, String id, List<Object> valueList) throws InvalidSPDXAnalysisException {
		ArrayList<JsonObject> retval = new ArrayList<>();
		for (Object value:valueList) {
			if (!(value instanceof TypedValue)) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+value.getClass().toString());
			}
			TypedValue tvValue = (TypedValue)value;
			if (!SpdxConstants.CLASS_RELATIONSHIP.equals(tvValue.getType())) {
				throw new SpdxInvalidTypeException("Expected relationship type, value list element was of type "+tvValue.getType());
			}
			JsonObject relationship = new JsonObject();
			relationship.add(SpdxConstants.PROP_SPDX_ELEMENTID, new JsonPrimitive(id));
			Optional<Object> relatedSpdxElement = getValue(documentUri, tvValue.getId(), SpdxConstants.PROP_RELATED_SPDX_ELEMENT); 
			if (!relatedSpdxElement.isPresent()) {
				logger.warn("Missing related SPDX element for a relationship for "+id+".  Skipping the serialization of this relationship.");
				continue;
			}
			if (relatedSpdxElement.get() instanceof TypedValue) {
				String relatedElementId = ((TypedValue)relatedSpdxElement.get()).getId();
				relationship.add(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, new JsonPrimitive(relatedElementId));
			} else if (relatedSpdxElement.get() instanceof IndividualUriValue) {
				// external SPDX element
				String externalUri = ((IndividualUriValue)relatedSpdxElement.get()).getIndividualURI();
				if (!SpdxConstants.EXTERNAL_SPDX_ELEMENT_URI_PATTERN.matcher(externalUri).matches()) {
					throw new SpdxInvalidTypeException("SPDX element must be of SpdxElement or external SPDX element type.  URI does not match pattern for external element: "+externalUri);
				}
				ExternalSpdxElement externalElement = ExternalSpdxElement.uriToExternalSpdxElement(externalUri, this, documentUri, null);
				relationship.add(SpdxConstants.PROP_RELATED_SPDX_ELEMENT, new JsonPrimitive(externalElement.getExternalDocumentId() + ":" + externalElement.getExternalElementId()));
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
			relationship.add(SpdxConstants.PROP_RELATIONSHIP_TYPE, individualUriToJsonElement(documentUri, ((IndividualUriValue)type.get()).getIndividualURI()));
			retval.add(relationship);
		}
		return retval;

	}

	/**
	 * This method is used for the extracted licenses otherwise just the ID of the license would be written
	 * @param documentUri  Document namespace or Uri
	 * @param id document ID containing the extracted licenses
	 * @param propertyName property name for the extracted licenses
	 * @param relationships list of relationships - just so we can pass it to toJsonObject
	 * @return a Json array of extracted license elements
	 * @throws InvalidSPDXAnalysisException
	 */
	private JsonElement toExtractedLicensesJson(String documentUri, String id, String propertyName, JsonArray relationships) throws InvalidSPDXAnalysisException {
		JsonArray ja = new JsonArray();
		List<Object> extractedLicenses = this.getValueList(documentUri, id, propertyName);
		for (Object extractedLicense:extractedLicenses) {
			if (!(extractedLicense instanceof TypedValue) || 
					(!SpdxConstants.CLASS_SPDX_EXTRACTED_LICENSING_INFO.equals(((TypedValue)extractedLicense).getType()))) {
				throw new SpdxInvalidTypeException("Extracted License Infos not of type "+SpdxConstants.CLASS_SPDX_EXTRACTED_LICENSING_INFO);
			}
			ja.add(toJsonObject(documentUri, (TypedValue)extractedLicense, relationships));
		}
		return ja;
	}

	/**
	 * Convert a list of values to a JsonArray for serialization
	 * @param documentUri Document namespace or Uri
	 * @param valueList list of values to convert
	 * @param relationships running total of any relationships found - any relationships are added
	 * @return
	 * @throws InvalidSPDXAnalysisException 
	 */
	private JsonArray toJsonArray(String documentUri, List<Object> valueList, JsonArray relationships) throws InvalidSPDXAnalysisException {
		JsonArray retval = new JsonArray();
		for (Object value:valueList) {
			retval.add(toJsonElement(documentUri, value, relationships));
		}
		return retval;
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#deSerialize(java.io.InputStream, boolean)
	 */
	public String deSerialize(InputStream stream, boolean overwrite) throws InvalidSPDXAnalysisException, IOException {
		throw new RuntimeException("Unimplemented");
	}

}

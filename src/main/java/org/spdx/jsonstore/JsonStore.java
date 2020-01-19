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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.library.model.ExternalSpdxElement;
import org.spdx.library.model.IndividualUriValue;
import org.spdx.library.model.SpdxInvalidTypeException;
import org.spdx.library.model.TypedValue;
import org.spdx.library.model.enumerations.SpdxEnumFactory;
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
		JsonObject jsonDoc = toJsonObject(documentUri, document);
		// Add relationships
		JsonArray relationships = new JsonArray();
		this.getAllItems(documentUri, SpdxConstants.CLASS_RELATIONSHIP).forEach(tv -> {
			try {
				relationships.add(toJsonElement(documentUri, tv));
			} catch (InvalidSPDXAnalysisException e) {
				throw new RuntimeException(e);
			}
		});
		jsonDoc.add("relationships", relationships);
		JsonObject output = new JsonObject();
		output.add("Document", jsonDoc);
		//TODO: Fix up things like the relationships
		//TODO: How to handle license expressions
		JsonWriter writer = new JsonWriter(new OutputStreamWriter(stream));
		writer.setIndent("  ");
		try {
			gson.toJson(output, writer);
		} finally {
			writer.close();
		}
	}

	/**
	 * @param storedValue Value to convert to a JSON serializable form
	 * @return Json form of the item
	 * @throws InvalidSPDXAnalysisException 
	 */
	private JsonElement toJsonElement(String documentUri, Object storedValue) throws InvalidSPDXAnalysisException {
		if (storedValue instanceof IndividualUriValue) {
			return individualUriToJsonElement(documentUri, ((IndividualUriValue)storedValue).getIndividualURI());
		} else if (storedValue instanceof TypedValue) {
			return toJsonObject(documentUri, (TypedValue)storedValue);
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
	 * @param documentUri
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
	 * @param documentUri
	 * @param storedItem
	 * @return JsonObject representation of the value
	 * @throws InvalidSPDXAnalysisException
	 */
	private JsonObject toJsonObject(String documentUri, TypedValue storedItem) throws InvalidSPDXAnalysisException {
		//TODO: Check for special types we may want to just convert to strings like AnyLicenseInfo
		List<String> docPropNames = new ArrayList<String>(this.getPropertyValueNames(documentUri, storedItem.getId()));
		JsonObject retval = new JsonObject();
		for (String propertyName:docPropNames) {
			if (SpdxConstants.PROP_RELATIONSHIP.equals(propertyName)) {
				continue;
			}
			if (this.isCollectionProperty(documentUri, storedItem.getId(), propertyName)) {
				retval.add(propertyName, toJsonArray(documentUri, this.getValueList(documentUri, storedItem.getId(), propertyName)));
			} else {
				Optional<Object> value = this.getValue(documentUri, storedItem.getId(), propertyName);
				if (value.isPresent()) {
					retval.add(propertyName, toJsonElement(documentUri, value.get()));
				}
			}
		}
		return retval;
	}

	/**
	 * Convert a list of values ot a JsonArray for serialization
	 * @param documentUri
	 * @param valueList
	 * @return
	 * @throws InvalidSPDXAnalysisException 
	 */
	private JsonArray toJsonArray(String documentUri, List<Object> valueList) throws InvalidSPDXAnalysisException {
		JsonArray retval = new JsonArray();
		for (Object value:valueList) {
			retval.add(toJsonElement(documentUri, value));
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

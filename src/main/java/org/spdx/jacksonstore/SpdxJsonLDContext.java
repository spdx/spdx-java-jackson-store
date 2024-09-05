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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.spdx.core.InvalidSPDXAnalysisException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Singleton class which manages access to the JSON-LD context for SPDX
 * 
 * @author Gary O'Neall
 *
 */
public class SpdxJsonLDContext {
	
	static Set<String> LIST_CONTAINER_TYPES;
	static {
		Set<String> listContainerTypes = new HashSet<>();
		listContainerTypes.add("@list");
		listContainerTypes.add("@set");
		listContainerTypes.add("@index");
		// Non-list types: @language, @id, @graph, @type
		LIST_CONTAINER_TYPES = Collections.unmodifiableSet(listContainerTypes);
	}
	
	/**
	 * Maps XML Schema primitive types to Java classes supported by the SPDX stores. 
	 * See https://www.w3.org/TR/xmlschema-2 for a description of XML schema types.
	 */
	public static Map<String, Class<? extends Object>> XMLSCHEMA_TYPE_TO_JAVA_CLASS;
	static {
		Map<String, Class<? extends Object>> schemaToClass = new HashMap<>();
		schemaToClass.put("string", String.class);
		schemaToClass.put("boolean", Boolean.class);
		schemaToClass.put("decimal", String.class);
		schemaToClass.put("float", String.class);
		schemaToClass.put("double", String.class);
		schemaToClass.put("duration", String.class);
		schemaToClass.put("dateTime", String.class);
		schemaToClass.put("time", String.class);
		schemaToClass.put("date", String.class);
		schemaToClass.put("gYearMonth", String.class);
		schemaToClass.put("gYear", String.class);
		schemaToClass.put("gMonthDay", String.class);
		schemaToClass.put("gDay", String.class);
		schemaToClass.put("gMonth", String.class);
		schemaToClass.put("hexBinary", String.class);
		schemaToClass.put("base64Binary", String.class);
		schemaToClass.put("anyURI", String.class);
		schemaToClass.put("3QName", String.class);
		schemaToClass.put("NOTATION", String.class);
        
        // derived
		schemaToClass.put("normalizedString", String.class);
		schemaToClass.put("token", String.class);
		schemaToClass.put("language", String.class);
		schemaToClass.put("NMTOKEN", String.class);
		schemaToClass.put("NMTOKENS", String.class);
		schemaToClass.put("Name", String.class);
		schemaToClass.put("NCName", String.class);
		schemaToClass.put("ID", String.class);
		schemaToClass.put("IDREF", String.class);
		schemaToClass.put("IDREFS", String.class);
		schemaToClass.put("ENTITY", String.class);
		schemaToClass.put("ENTITIES", String.class);
		schemaToClass.put("integer", Integer.class);
		schemaToClass.put("nonPositiveInteger", Integer.class);
		schemaToClass.put("negativeInteger", Integer.class);
		schemaToClass.put("long", Integer.class);
		schemaToClass.put("int", Integer.class);
		schemaToClass.put("short", Integer.class);
		schemaToClass.put("byte", Integer.class);
		schemaToClass.put("nonNegativeInteger", Integer.class);
		schemaToClass.put("unsignedLong", Integer.class);
		schemaToClass.put("unsignedInt", Integer.class);
		schemaToClass.put("unsignedShort", Integer.class);
		schemaToClass.put("unsignedByte", Integer.class);
		schemaToClass.put("positiveInteger", Integer.class);
		XMLSCHEMA_TYPE_TO_JAVA_CLASS = Collections.unmodifiableMap(schemaToClass);
	}
	
	static private SpdxJsonLDContext instance;
	static final String JSON_LD_PATH = "/resources/spdx-2-3-revision-2-onotology.context.json";
	static final ObjectMapper JSON_MAPPER = new ObjectMapper();
	private JsonNode contexts;
	
	private SpdxJsonLDContext() throws InvalidSPDXAnalysisException {
		try (InputStream is = SpdxJsonLDContext.class.getResourceAsStream(JSON_LD_PATH)) {
			if (Objects.isNull(is)) {
				throw new RuntimeException("Unable to open JSON LD context file");
			}
			JsonNode root;
			try {
				root = JSON_MAPPER.readTree(is);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			JsonNode contexts = root.get("@context");
			if (Objects.isNull(contexts)) {
				throw new InvalidSPDXAnalysisException("Missing contexts");
			}
			if (!contexts.isObject()) {
				throw new InvalidSPDXAnalysisException("Contexts is not an object");
			}
			this.contexts = contexts;
		} catch (IOException e1) {
			throw new RuntimeException(e1);
		}
	}
	
	public static synchronized SpdxJsonLDContext getInstance() throws InvalidSPDXAnalysisException {
		if (Objects.isNull(instance)) {
			instance = new SpdxJsonLDContext();
		}
		return instance;
	}
	
	/**
	 * @param propertyName
	 * @return type type for the property name if the type is specified in the JSON-LD context
	 */
	public Optional<String> getType(String propertyName) {
		JsonNode propContext = this.contexts.get(propertyName);
		if (Objects.isNull(propContext)) {
			return Optional.empty();
		}
		JsonNode jnType = propContext.get("@type");
		if (Objects.isNull(jnType)) {
			return Optional.empty();
		}
		String type = null;
		if (jnType.isArray()) {
			Iterator<JsonNode> iter = jnType.iterator();
			while (iter.hasNext() && Objects.isNull(type)) {
				JsonNode jnTypeElement = iter.next();
				if (Objects.nonNull(jnTypeElement)) {
					String stTypeElement = jnType.asText();
					if (Objects.nonNull(stTypeElement) && !(stTypeElement.startsWith("@"))) {
						type = stTypeElement;
					}
				}
			}
		} else {
			type = jnType.asText();
		}
		if (Objects.isNull(type)) {
			return Optional.empty();
		}
		int indexOfColon = type.lastIndexOf(':');
		if (indexOfColon > 0) {
			type = type.substring(indexOfColon+1);
		}
		return Optional.of(type);
	}

	/**
	 * @param property
	 * @return true if the property is a list
	 */
	public boolean isList(String property) {
		JsonNode propContext = this.contexts.get(property);
		if (Objects.isNull(propContext)) {
			return false;	// default
		}
		JsonNode jnContainer = propContext.get("@container");
		if (Objects.isNull(jnContainer)) {
			return false;
		}
		if (jnContainer.isArray()) {
			Iterator<JsonNode> iter = jnContainer.iterator();
			while (iter.hasNext()) {
				if (LIST_CONTAINER_TYPES.contains(iter.next().asText())) {
					return true;
				}
			}
			return false;
		} else {
			return LIST_CONTAINER_TYPES.contains(jnContainer.asText());
		}
	}

}

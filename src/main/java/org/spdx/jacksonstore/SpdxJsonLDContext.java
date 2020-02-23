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
import java.util.Objects;
import java.util.Optional;

import org.spdx.library.InvalidSPDXAnalysisException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Singleton class which manages access to the JSON-LD context for SPDX
 * 
 * @author Gary O'Neall
 *
 */
public class SpdxJsonLDContext {
	
	static private SpdxJsonLDContext instance;
	static final String JSON_LD_PATH = "/resources/spdx-2-2-revision-5-onotology.context.json";
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
		String type = jnType.asText();
		if (Objects.isNull(type)) {
			return Optional.empty();
		}
		int indexOfColon = type.lastIndexOf(':');
		if (indexOfColon > 0) {
			type = type.substring(indexOfColon+1);
		}
		return Optional.of(type);
	}

}

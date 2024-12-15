/**
 * Copyright (c) 2020 Source Auditor Inc.
 * <p>
 * SPDX-License-Identifier: Apache-2.0
 * <p>
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * <p>
 *       http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.spdx.jacksonstore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.spdx.library.model.v2.SpdxConstantsCompatV2;

/**
 * Comparator for property names for different SPDX class types
 * <p>
 * To change the order of properties, change the order in the arrays for the specific type
 * If a property or type isn't in the propertyOrderMap, the alphaNumeric comparison of property names will be used
 * 
 * @author Gary O'Neall
 *
 */
public class PropertyComparator implements Comparator<String> {
	
	static final List<String> DOCUMENT_PROPERTY_ORDER = Arrays.asList(SpdxConstantsCompatV2.PROP_DOCUMENT_NAMESPACE.getName(),
            SpdxConstantsCompatV2.PROP_SPDX_SPEC_VERSION.getName(),
            SpdxConstantsCompatV2.PROP_SPDX_CREATION_INFO.getName(),
            SpdxConstantsCompatV2.PROP_NAME.getName(),
            SpdxConstantsCompatV2.PROP_SPDX_DATA_LICENSE.getName(),
            SpdxConstantsCompatV2.RDFS_PROP_COMMENT.getName(),
            SpdxConstantsCompatV2.PROP_SPDX_EXTERNAL_DOC_REF.getName(),
            SpdxConstantsCompatV2.PROP_DOCUMENT_DESCRIBES.getName(),
            SpdxConstantsCompatV2.PROP_DOCUMENT_PACKAGES.getName(),
            SpdxConstantsCompatV2.PROP_DOCUMENT_FILES.getName(),
            SpdxConstantsCompatV2.PROP_DOCUMENT_SNIPPETS.getName(),
            SpdxConstantsCompatV2.PROP_SPDX_EXTRACTED_LICENSES.getName(),
            SpdxConstantsCompatV2.PROP_ANNOTATION.getName(),
            SpdxConstantsCompatV2.PROP_DOCUMENT_RELATIONSHIPS.getName());
	static final Map<String, List<String>> propertyOrderMap;
	static {
		HashMap<String, List<String>> hm = new HashMap<>();
		hm.put(SpdxConstantsCompatV2.CLASS_SPDX_DOCUMENT, DOCUMENT_PROPERTY_ORDER);
		propertyOrderMap = Collections.unmodifiableMap(hm);
	}
	private List<String> propertyOrder;

	public PropertyComparator(String type) {
		propertyOrder = propertyOrderMap.get(type);
		if (Objects.isNull(propertyOrder)) {
			propertyOrder = new ArrayList<>();
		}
	}

	@Override
	public int compare(String o1, String o2) {
		int i1 = propertyOrder.indexOf(o1);
		if (i1 < 0) {
			i1 = 999;
		}
		int i2 = propertyOrder.indexOf(o2);
		if (i2 < 0) {
			i2 = 999;
		}
		int retval = Integer.compare(i1, i2);
		if (retval == 0) {
			retval = o1.compareTo(o2);
		}
		return retval;
	}

}

/**
 * Copyright (c) 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.example.serviceloader.pitfalls;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.example.serviceloader.api.Greeter;

final class Greeters {

	private Greeters() {
	}

	/**
	 * @return the provider class names, or the message of a
	 *         {@link ServiceConfigurationError} in place of a provider
	 */
	static List<String> names(ServiceLoader<Greeter> loader) {
		List<String> result = new ArrayList<>();
		Iterator<Greeter> greeters = loader.iterator();
		// a ServiceConfigurationError skips the offending provider; bounded in case it does not
		for (int i = 0; i < 20; i++) {
			try {
				if (!greeters.hasNext()) {
					break;
				}
				result.add(greeters.next().getClass().getName());
			} catch (ServiceConfigurationError e) {
				result.add("ServiceConfigurationError: " + e.getMessage());
			}
		}
		return result;
	}
}

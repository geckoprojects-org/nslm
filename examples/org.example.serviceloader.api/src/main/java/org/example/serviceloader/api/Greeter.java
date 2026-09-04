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
package org.example.serviceloader.api;

import org.osgi.annotation.versioning.ConsumerType;

/**
 * The service type that is looked up through {@code java.util.ServiceLoader}.
 */
@ConsumerType
public interface Greeter {

	/**
	 * @return a greeting in the language of this greeter
	 */
	String greet();
}

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
package org.example.serviceloader.provider;

import org.example.serviceloader.api.Greeter;

/**
 * A plain Java ServiceLoader provider: registered in META-INF/services and in
 * module-info.java, no OSGi metadata at all.
 */
public class EnglishGreeter implements Greeter {

	@Override
	public String greet() {
		return "Hello from the English greeter";
	}
}

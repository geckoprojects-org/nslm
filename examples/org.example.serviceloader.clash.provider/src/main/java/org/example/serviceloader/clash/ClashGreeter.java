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
package org.example.serviceloader.clash;

import org.example.serviceloader.api.Greeter;

/** Greeter 1.0; the 2.0 clash provider ships a class with exactly this name */
public class ClashGreeter implements Greeter {

	@Override
	public String greet() {
		return "Hello from the 1.0 clash greeter";
	}
}

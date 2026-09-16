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
package org.example.serviceloader.tests.selfcontained;

/**
 * A service type in a package that its bundle neither imports nor exports: the
 * shape of a wrapped library that keeps its SPI internal. There is no package
 * capability to compare class spaces with, and no foreign bundle can be type
 * compatible - but the bundle's own providers must still be found.
 */
public interface Codec {

	String name();
}

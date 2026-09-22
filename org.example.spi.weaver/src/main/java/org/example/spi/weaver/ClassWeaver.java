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
package org.example.spi.weaver;

import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.framework.wiring.BundleWiring;

/** One technique of {@link ServiceLoaderWeavingHook}. */
interface ClassWeaver {

	/**
	 * @param className binary name of the class, for tracing
	 * @param bytes the class file
	 * @param wiring the wiring of the bundle the class belongs to, or {@code null}
	 * @param changes incremented by the number of call sites or method
	 *            references changed
	 * @return the woven class file, or {@code null} if nothing was changed
	 */
	byte[] weave(String className, byte[] bytes, BundleWiring wiring, AtomicInteger changes);
}

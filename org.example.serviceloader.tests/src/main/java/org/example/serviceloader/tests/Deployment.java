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
package org.example.serviceloader.tests;

import org.osgi.framework.BundleContext;

/**
 * The mediator deployments of the bndruns (framework property
 * {@code test.deployment}), grouped by how they find the calling bundle. Within
 * a group all runs behave the same, on Felix and on Equinox.
 */
enum Deployment {

	/**
	 * {@code weaver}, {@code weaver-equinox}, {@code weaver-java21},
	 * {@code weaver-java21-equinox}: the woven call site knows its bundle, the
	 * TCCL plays no part (except for calls the weaver cannot see).
	 */
	WEAVER,

	/**
	 * {@code mediator}, {@code mediator-equinox}, {@code equinox-extension}: the
	 * mediator sees the TCCL and the bundle class loaders, the calling bundle
	 * is whatever the thread context says.
	 */
	TCCL;

	static Deployment of(BundleContext context) {
		String deployment = context.getProperty("test.deployment");
		if (deployment == null) {
			throw new IllegalStateException("test.deployment is not set in the -runproperties of this bndrun");
		}
		return deployment.startsWith("weaver") ? WEAVER : TCCL;
	}
}

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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.example.serviceloader.tests.selfcontained.Codec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

/**
 * A bundle that carries service type, provider and META-INF/services itself, in
 * a package it neither imports nor exports - the shape of a wrapped library with
 * an internal SPI. There is no package capability for such a package, so the
 * class space filter has nothing to compare; it must not answer with nothing,
 * because a bundle's own providers are by definition in its own class space and
 * the plain ServiceLoader would find them through the bundle class loader.
 */
@ExtendWith(BundleContextExtension.class)
public class SelfContainedServiceLoaderTest {

	static final String CODEC_PACKAGE = "org.example.serviceloader.tests.selfcontained";

	@InjectBundleContext
	BundleContext context;

	/** guards the premise: an exported or imported package would not test anything */
	@Test
	void theServiceTypePackageIsPrivateToThisBundle() {
		Bundle self = context.getBundle();

		assertThat(String.valueOf(self.getHeaders().get("Export-Package"))).doesNotContain(CODEC_PACKAGE);
		assertThat(String.valueOf(self.getHeaders().get("Import-Package"))).doesNotContain(CODEC_PACKAGE);
	}

	@Test
	void ownProvidersOfAPrivateServiceTypeAreFound() {
		assertThat(names(ServiceLoader.load(Codec.class))).containsExactly("plain");
	}

	/** the explicit class loader argument takes the same route */
	@Test
	void ownProvidersAreAlsoFoundWithBundleClassLoaderArgument() {
		assertThat(names(ServiceLoader.load(Codec.class, Codec.class.getClassLoader()))).containsExactly("plain");
	}

	/**
	 * The iteration has to happen here, in a frame of this bundle. java.util.ServiceLoader
	 * resolves lazily, and the TCCL of the launcher based mediator has no consumer bound to
	 * it: it determines the asking bundle from the stack when getResources() is called. Left
	 * to an assertion library, the first bundle frame would be that library.
	 */
	private static List<String> names(ServiceLoader<Codec> loader) {
		List<String> names = new ArrayList<>();
		for (Codec codec : loader) {
			names.add(codec.name());
		}
		return names;
	}
}

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

import java.util.List;

import org.junit.jupiter.api.Test;

import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.RuntimeDelegate;

/**
 * Jakarta REST 4.0 and Jakarta Persistence 3.1, the two APIs whose search
 * paths use {@code ServiceLoader.load(type, TCCL)}. REST:
 * {@code RuntimeDelegate.getInstance()} runs the API's {@code FactoryFinder}
 * ({@code $java.home/lib/jaxrs.properties}, system property, ServiceLoader with
 * the TCCL, Jersey's class name as default) and every {@code Response} and
 * {@code UriBuilder} goes through it. Persistence: the default
 * {@code PersistenceProviderResolver} asks {@code ServiceLoader.load(PersistenceProvider.class, TCCL)}
 * for the provider list. Jersey 4.0.2 (jersey-common) and EclipseLink 4.0.9 are
 * installed as plain bundles. No server, no database: the lookups are the point.
 */
public class RestAndPersistenceServiceLoaderTest {

	@Test
	void restRuntimeDelegateComesFromJersey() {
		RuntimeDelegate delegate = RuntimeDelegate.getInstance();

		assertThat(delegate.getClass().getName()).startsWith("org.glassfish.jersey");
		assertThat(Response.ok("hello osgi").build().getStatus()).isEqualTo(200);
		assertThat(UriBuilder.fromPath("/greeter/{lang}").build("de").toString()).isEqualTo("/greeter/de");
	}

	@Test
	void persistenceProviderResolverFindsEclipseLink() {
		List<PersistenceProvider> providers = PersistenceProviderResolverHolder.getPersistenceProviderResolver()
			.getPersistenceProviders();

		assertThat(providers).extracting(p -> p.getClass().getName())
			.containsExactly("org.eclipse.persistence.jpa.PersistenceProvider");
	}
}

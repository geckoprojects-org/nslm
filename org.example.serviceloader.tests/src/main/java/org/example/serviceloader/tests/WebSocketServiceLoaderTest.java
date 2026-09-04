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

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

/**
 * Real-world API without any OSGi ServiceLoader metadata:
 * {@code jakarta.websocket-client-api} calls
 * {@code ServiceLoader.load(ContainerProvider.class)}; Tyrus' grizzly client
 * container declares its provider only in module-info (provides), no
 * META-INF/services. The registry reads both.
 */
@ExtendWith(BundleContextExtension.class)
public class WebSocketServiceLoaderTest {

	static final String TYRUS_GRIZZLY_CLIENT_BSN = "org.glassfish.tyrus.container-grizzly-client";

	@InjectBundleContext
	BundleContext context;

	@Test
	void containerProviderIsFound() {
		WebSocketContainer container = withTyrusContextClassLoader(ContainerProvider::getWebSocketContainer);

		assertThat(container).isNotNull();
		assertThat(container.getClass().getName()).startsWith("org.glassfish.tyrus");
	}

	/**
	 * Not a ServiceLoader issue: Tyrus' {@code ClientManager.createClient(String)}
	 * loads its container class by name via the TCCL / tyrus-core, which cannot
	 * see the grizzly client container package. The provider itself was already
	 * delivered by the mediator at that point.
	 */
	private <T> T withTyrusContextClassLoader(Supplier<T> call) {
		Bundle grizzlyClient = TestSupport.bundle(context, TYRUS_GRIZZLY_CLIENT_BSN);
		ClassLoader tyrusLoader = grizzlyClient.adapt(BundleWiring.class).getClassLoader();
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(tyrusLoader);
		try {
			return call.get();
		} finally {
			thread.setContextClassLoader(previous);
		}
	}
}

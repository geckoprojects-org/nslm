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
package org.example.serviceloader.consumer.rb;

import java.util.ServiceLoader;

import org.example.serviceloader.api.Greeter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

/**
 * Like {@code GreeterConsumer}, but wired to the Greeter API with Require-Bundle.
 * The consumer has no {@code osgi.wiring.package} wire for the API package, so its
 * class space is only visible through the {@code osgi.wiring.bundle} wire to the
 * API bundle. It must still see exactly the providers of Greeter 1.0.
 */
@Component
public class GreeterConsumerRb {

	@Activate
	void activate() {
		int count = 0;
		for (Greeter greeter : ServiceLoader.load(Greeter.class)) {
			count++;
			System.out.println("[GreeterConsumerRb] " + greeter.getClass().getName() + ": " + greeter.greet());
		}
		System.out.println("[GreeterConsumerRb] found " + count + " Greeter provider(s) via java.util.ServiceLoader");
	}

}

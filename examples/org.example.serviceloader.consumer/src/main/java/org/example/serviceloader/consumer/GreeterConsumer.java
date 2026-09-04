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
package org.example.serviceloader.consumer;

import java.util.ServiceLoader;

import org.example.serviceloader.api.Greeter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

/**
 * Plain consumer: this bundle has no OSGi Service Loader Mediator metadata and
 * no helper class, it calls {@code ServiceLoader.load(Greeter.class)} right in
 * the DS activate method. Inside OSGi the lookup only works because a mediator
 * (weaver or launcher based) makes java.util.ServiceLoader see the providers of
 * this bundle's class space.
 */
@Component
public class GreeterConsumer {

	@Activate
	void activate() {
		int count = 0;
		for (Greeter greeter : ServiceLoader.load(Greeter.class)) {
			count++;
			System.out.println("[GreeterConsumer] " + greeter.getClass().getName() + ": " + greeter.greet());
		}
		System.out.println("[GreeterConsumer] found " + count + " Greeter provider(s) via java.util.ServiceLoader");
	}

}

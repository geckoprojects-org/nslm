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
package org.example.serviceloader.consumer.v2;

import java.util.ServiceLoader;

import org.example.serviceloader.api.Greeter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

@Component
public class GreeterConsumerV2 {

	@Activate
	void activate() {
		int count = 0;
		for (Greeter greeter : ServiceLoader.load(Greeter.class)) {
			count++;
			System.out.println("[GreeterConsumerV2] " + greeter.getClass().getName() + " (" + greeter.language() + "): " + greeter.greet());
		}
		System.out.println("[GreeterConsumerV2] found " + count + " Greeter 2.0 provider(s) via java.util.ServiceLoader");
	}
}

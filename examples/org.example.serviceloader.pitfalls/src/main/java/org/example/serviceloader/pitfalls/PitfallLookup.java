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
package org.example.serviceloader.pitfalls;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;

import org.example.serviceloader.api.Greeter;
import org.osgi.service.component.annotations.Component;

/**
 * The plain lookup of this bundle, {@code ServiceLoader.load(Greeter.class)}
 * for Greeter 2.0, as a task: the test runs it on threads of its choice (common
 * pool, a pool thread with a stale TCCL, a thread without TCCL, a thread whose
 * TCCL was not restored).
 */
@Component(service = Callable.class, property = "pitfall=lookup")
public class PitfallLookup implements Callable<List<String>> {

	@Override
	public List<String> call() {
		return Greeters.names(ServiceLoader.load(Greeter.class));
	}
}

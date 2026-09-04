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
package org.example.serviceloader.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The same loops on a flat class path without OSGi and without any mediator (surefire). */
class PlainBaselineTest {

	@Test
	void baseline() {
		var results = Bench.run(Bench.warmup(), Bench.iterations());
		Bench.report("plain", results);
		assertEquals(2, (int) results.get("load + iterate")[1]);
	}
}

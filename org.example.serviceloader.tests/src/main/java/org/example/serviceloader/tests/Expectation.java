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

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The correct result of a lookup, plus the results a {@link Deployment} is
 * known to produce instead, each with its reason. A deployment without a known
 * limitation must produce the correct result; a deployment with one must
 * produce exactly the known result, so that a fix (or a new failure) shows up
 * as a failing test and the expectation gets updated.
 */
final class Expectation<T> {

	private record Known<T>(Predicate<T> observed, String description, String why) {
	}

	private final T correct;
	private final Map<Deployment, Known<T>> known = new EnumMap<>(Deployment.class);

	private Expectation(T correct) {
		this.correct = correct;
	}

	static <T> Expectation<T> correct(T correct) {
		return new Expectation<>(correct);
	}

	/** the deployment is known to produce {@code observed} instead of the correct result */
	Expectation<T> known(Deployment deployment, T observed, String why) {
		return known(deployment, observed::equals, String.valueOf(observed), why);
	}

	/** the deployment is known to produce a result that matches {@code observed} */
	Expectation<T> known(Deployment deployment, Predicate<T> observed, String description, String why) {
		known.put(deployment, new Known<>(observed, description, why));
		return this;
	}

	void verify(Deployment deployment, T actual) {
		Known<T> limitation = known.get(deployment);
		if (limitation == null) {
			assertThat(actual).as("%s", deployment).isEqualTo(correct);
			return;
		}
		assertThat(actual).as("%s, known limitation: %s", deployment, limitation.why())
			.matches(limitation.observed(), limitation.description());
	}
}

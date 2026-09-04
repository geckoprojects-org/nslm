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

import org.junit.jupiter.api.Test;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.spi.JsonbProvider;
import jakarta.json.spi.JsonProvider;

/**
 * Two more Jakarta APIs with their own search order, as they come from Maven
 * Central. JSON-P: {@code JsonProvider.provider()} tries the system property
 * {@code jakarta.json.provider}, then {@code ServiceLoader.load(JsonProvider.class)},
 * then HK2's OSGi resource locator, then the default class name
 * {@code org.eclipse.parsson.JsonProviderImpl} by {@code Class.forName}, which
 * fails in OSGi because the API bundle does not import that package. JSON-B:
 * {@code JsonbProvider.provider()} tries {@code ServiceLoader.load(JsonbProvider.class)}
 * and then the default class name {@code org.eclipse.yasson.JsonBindingProvider}.
 * Yasson's provider in turn asks {@code JsonProvider.provider()} for its JSON-P
 * implementation, so JSON-B is a two level ServiceLoader chain through two
 * different API bundles.
 */
public class JsonServiceLoaderTest {

	public static class Note {
		public String text = "hello osgi";
	}

	@Test
	void jsonpProviderIsFoundThroughServiceLoader() {
		JsonProvider provider = JsonProvider.provider();

		assertThat(provider.getClass().getName()).startsWith("org.eclipse.parsson");
		JsonObject object = Json.createObjectBuilder().add("text", "hello osgi").build();
		assertThat(object.toString()).isEqualTo("{\"text\":\"hello osgi\"}");
	}

	@Test
	void jsonbProviderIsFoundThroughServiceLoaderAndUsesJsonp() throws Exception {
		JsonbProvider provider = JsonbProvider.provider();
		assertThat(provider.getClass().getName()).startsWith("org.eclipse.yasson");

		try (Jsonb jsonb = JsonbBuilder.create()) {
			String json = jsonb.toJson(new Note());
			assertThat(json).isEqualTo("{\"text\":\"hello osgi\"}");
			assertThat(jsonb.fromJson(json, Note.class).text).isEqualTo("hello osgi");
		}
	}
}

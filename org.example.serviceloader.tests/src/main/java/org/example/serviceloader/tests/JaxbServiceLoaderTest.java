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

import java.io.StringReader;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * Real-world Jakarta API as it comes from Maven Central: {@code jakarta.xml.bind-api}
 * looks up its {@code JAXBContextFactory} with {@code ServiceLoader.load(Class)},
 * the GlassFish runtime is the provider.
 */
public class JaxbServiceLoaderTest {

	@XmlRootElement(name = "note")
	public static class Note {
		@XmlElement
		public String text;
	}

	@Test
	void jaxbContextIsCreatedThroughServiceLoader() throws JAXBException {
		JAXBContext jaxbContext = JAXBContext.newInstance(Note.class);

		assertThat(jaxbContext.getClass().getName()).startsWith("org.glassfish.jaxb.runtime");
	}

	@Test
	void marshalAndUnmarshalRoundTrip() throws JAXBException {
		JAXBContext jaxbContext = JAXBContext.newInstance(Note.class);
		Note note = new Note();
		note.text = "hello osgi";

		StringWriter xml = new StringWriter();
		jaxbContext.createMarshaller().marshal(note, xml);
		assertThat(xml.toString()).contains("<note>").contains("<text>hello osgi</text>");

		Note read = (Note) jaxbContext.createUnmarshaller().unmarshal(new StringReader(xml.toString()));
		assertThat(read.text).isEqualTo("hello osgi");
	}
}

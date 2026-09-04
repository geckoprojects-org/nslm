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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamReader;

import org.junit.jupiter.api.Test;

/**
 * A ServiceLoader call inside the JDK, which no weaver can reach:
 * {@code XMLInputFactory.newInstance()} runs {@code javax.xml.stream.FactoryFinder},
 * which tries the system property, {@code $java.home/conf/stax.properties} and
 * then {@code ServiceLoader.load(XMLInputFactory.class)} with the thread context
 * class loader before it falls back to the JDK's own implementation. Woodstox is
 * installed as a plain bundle with its {@code META-INF/services} files. The
 * lookup only reaches it when the TCCL knows the registry: the launcher based
 * mediator sets that TCCL before the framework starts, the weaver only with
 * {@code spi.weaver.tccl=true}.
 */
public class JdkFactoryServiceLoaderTest {

	@Test
	void staxFactoriesComeFromWoodstoxThroughTheTccl() throws Exception {
		XMLInputFactory input = XMLInputFactory.newInstance();
		XMLOutputFactory output = XMLOutputFactory.newInstance();

		assertThat(input.getClass().getName()).isEqualTo("com.ctc.wstx.stax.WstxInputFactory");
		assertThat(output.getClass().getName()).isEqualTo("com.ctc.wstx.stax.WstxOutputFactory");

		XMLStreamReader reader = input.createXMLStreamReader(new StringReader("<note>hello osgi</note>"));
		reader.nextTag();
		assertThat(reader.getLocalName()).isEqualTo("note");
		assertThat(reader.getElementText()).isEqualTo("hello osgi");
	}
}

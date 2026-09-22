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
package org.example.serviceloader.jaxp;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

/** A SAXParserFactory provider that delegates to the JDK's built-in one. */
public class ExampleSAXParserFactory extends SAXParserFactory {

	private final SAXParserFactory delegate = SAXParserFactory.newDefaultInstance();

	@Override
	public SAXParser newSAXParser() throws ParserConfigurationException, SAXException {
		delegate.setNamespaceAware(isNamespaceAware());
		delegate.setValidating(isValidating());
		return delegate.newSAXParser();
	}

	@Override
	public void setFeature(String name, boolean value)
		throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
		delegate.setFeature(name, value);
	}

	@Override
	public boolean getFeature(String name)
		throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
		return delegate.getFeature(name);
	}
}

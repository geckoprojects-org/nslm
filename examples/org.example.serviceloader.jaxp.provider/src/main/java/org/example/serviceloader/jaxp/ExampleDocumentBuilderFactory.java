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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** A DocumentBuilderFactory provider that delegates to the JDK's built-in one. */
public class ExampleDocumentBuilderFactory extends DocumentBuilderFactory {

	private final DocumentBuilderFactory delegate = DocumentBuilderFactory.newDefaultInstance();

	@Override
	public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
		delegate.setNamespaceAware(isNamespaceAware());
		delegate.setValidating(isValidating());
		return delegate.newDocumentBuilder();
	}

	@Override
	public void setAttribute(String name, Object value) {
		delegate.setAttribute(name, value);
	}

	@Override
	public Object getAttribute(String name) {
		return delegate.getAttribute(name);
	}

	@Override
	public void setFeature(String name, boolean value) throws ParserConfigurationException {
		delegate.setFeature(name, value);
	}

	@Override
	public boolean getFeature(String name) throws ParserConfigurationException {
		return delegate.getFeature(name);
	}
}

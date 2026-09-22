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
package org.example.spi.weaver;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.validation.SchemaFactory;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;

/**
 * Redirect targets for the JDK factory methods that look up their
 * implementation with {@code ServiceLoader} through the thread context class
 * loader, inside the JDK where no weaver reaches: StAX and JAXP. Each method has
 * the name and descriptor of the JDK method and is reached through a constant
 * pool redirect ({@link #RULES}) of the calling bundle class. It makes the
 * {@code SpiClassLoader} bound to the calling bundle the TCCL for the duration
 * of the original call, so the lookup sees the providers of the caller's class
 * space on any thread: a common pool thread, a thread without TCCL, a pool
 * thread with a stale foreign TCCL. The variants with an explicit class loader
 * are left alone.
 * <p>
 * One nested class per JDK factory, because methods such as
 * {@code XMLInputFactory.newInstance()} and {@code XMLOutputFactory.newInstance()}
 * differ in their return type only.
 */
public final class JdkFactories {

	private static final String PREFIX = JdkFactories.class.getName().replace('.', '/') + "$";

	/** the constant pool redirects of the JDK factory methods */
	static final List<ConstantPoolPatcher.Rule> RULES = List.of(
		rule("javax/xml/stream/XMLInputFactory", "XMLInputFactoryRedirect", "newInstance()", "newFactory()"),
		rule("javax/xml/stream/XMLOutputFactory", "XMLOutputFactoryRedirect", "newInstance()", "newFactory()"),
		rule("javax/xml/stream/XMLEventFactory", "XMLEventFactoryRedirect", "newInstance()", "newFactory()"),
		rule("javax/xml/parsers/DocumentBuilderFactory", "DocumentBuilderFactoryRedirect", "newInstance()",
			"newNSInstance()"),
		rule("javax/xml/parsers/SAXParserFactory", "SAXParserFactoryRedirect", "newInstance()", "newNSInstance()"),
		rule("javax/xml/transform/TransformerFactory", "TransformerFactoryRedirect", "newInstance()"),
		rule("javax/xml/xpath/XPathFactory", "XPathFactoryRedirect", "newInstance()", "newInstance(Ljava/lang/String;)"),
		rule("javax/xml/validation/SchemaFactory", "SchemaFactoryRedirect", "newInstance(Ljava/lang/String;)"),
		rule("javax/xml/datatype/DatatypeFactory", "DatatypeFactoryRedirect", "newInstance()"));

	private JdkFactories() {
	}

	/** @param signatures {@code name(parameters)}; the return type is the factory itself */
	private static ConstantPoolPatcher.Rule rule(String owner, String redirect, String... signatures) {
		Set<String> methods = new HashSet<>();
		for (String signature : signatures) {
			methods.add(signature + "L" + owner + ";");
		}
		return new ConstantPoolPatcher.Rule(owner, PREFIX + redirect, Set.copyOf(methods));
	}

	/** checked exceptions of the factory methods pass through */
	@FunctionalInterface
	interface Factory<T, E extends Exception> {
		T create() throws E;
	}

	/**
	 * Runs the original factory method with the calling bundle's
	 * {@code SpiClassLoader} as TCCL; unchanged if the weaver is not active or the
	 * caller is not a bundle class.
	 */
	static <T, E extends Exception> T withCallerTccl(Factory<T, E> factory) throws E {
		SpiLoaders active = ServiceLoaders.active();
		ClassLoader loader = active == null ? null : active.loaderFor(ServiceLoaders.caller());
		if (loader == null) {
			return factory.create();
		}
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(loader);
		try {
			return factory.create();
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	private static <T> T unchecked(Supplier<T> factory) {
		return withCallerTccl(factory::get);
	}

	public static final class XMLInputFactoryRedirect {
		private XMLInputFactoryRedirect() {
		}

		public static XMLInputFactory newInstance() {
			return unchecked(XMLInputFactory::newInstance);
		}

		public static XMLInputFactory newFactory() {
			return unchecked(XMLInputFactory::newFactory);
		}
	}

	public static final class XMLOutputFactoryRedirect {
		private XMLOutputFactoryRedirect() {
		}

		public static XMLOutputFactory newInstance() {
			return unchecked(XMLOutputFactory::newInstance);
		}

		public static XMLOutputFactory newFactory() {
			return unchecked(XMLOutputFactory::newFactory);
		}
	}

	public static final class XMLEventFactoryRedirect {
		private XMLEventFactoryRedirect() {
		}

		public static XMLEventFactory newInstance() {
			return unchecked(XMLEventFactory::newInstance);
		}

		public static XMLEventFactory newFactory() {
			return unchecked(XMLEventFactory::newFactory);
		}
	}

	public static final class DocumentBuilderFactoryRedirect {
		private DocumentBuilderFactoryRedirect() {
		}

		public static DocumentBuilderFactory newInstance() {
			return unchecked(DocumentBuilderFactory::newInstance);
		}

		public static DocumentBuilderFactory newNSInstance() {
			return unchecked(DocumentBuilderFactory::newNSInstance);
		}
	}

	public static final class SAXParserFactoryRedirect {
		private SAXParserFactoryRedirect() {
		}

		public static SAXParserFactory newInstance() {
			return unchecked(SAXParserFactory::newInstance);
		}

		public static SAXParserFactory newNSInstance() {
			return unchecked(SAXParserFactory::newNSInstance);
		}
	}

	public static final class TransformerFactoryRedirect {
		private TransformerFactoryRedirect() {
		}

		public static TransformerFactory newInstance() {
			return unchecked(TransformerFactory::newInstance);
		}
	}

	public static final class XPathFactoryRedirect {
		private XPathFactoryRedirect() {
		}

		public static XPathFactory newInstance() {
			return unchecked(XPathFactory::newInstance);
		}

		public static XPathFactory newInstance(String uri) throws XPathFactoryConfigurationException {
			return withCallerTccl(() -> XPathFactory.newInstance(uri));
		}
	}

	public static final class SchemaFactoryRedirect {
		private SchemaFactoryRedirect() {
		}

		public static SchemaFactory newInstance(String schemaLanguage) {
			return unchecked(() -> SchemaFactory.newInstance(schemaLanguage));
		}
	}

	public static final class DatatypeFactoryRedirect {
		private DatatypeFactoryRedirect() {
		}

		public static DatatypeFactory newInstance() throws DatatypeConfigurationException {
			return withCallerTccl(DatatypeFactory::newInstance);
		}
	}
}

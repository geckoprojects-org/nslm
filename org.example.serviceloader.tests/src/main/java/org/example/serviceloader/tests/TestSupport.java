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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;

final class TestSupport {

	private TestSupport() {
	}

	static Bundle bundle(BundleContext context, String bsn) {
		for (Bundle bundle : context.getBundles()) {
			if (bsn.equals(bundle.getSymbolicName())) {
				return bundle;
			}
		}
		throw new AssertionError("bundle not installed: " + bsn);
	}

	/** stop + start the bundle while System.out is captured; waits for {@code marker} */
	static String restartAndCapture(Bundle bundle, String marker) {
		return captureOutput(() -> {
			try {
				bundle.stop();
				bundle.start();
			} catch (BundleException e) {
				throw new IllegalStateException(e);
			}
		}, marker);
	}

	static String captureOutput(Runnable action, String marker) {
		PrintStream original = System.out;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		OutputStream tee = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				buffer.write(b);
				original.write(b);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				buffer.write(b, off, len);
				original.write(b, off, len);
			}

			@Override
			public void flush() {
				original.flush();
			}
		};
		System.setOut(new PrintStream(tee, true, StandardCharsets.UTF_8));
		try {
			action.run();
			await(() -> buffer.toString(StandardCharsets.UTF_8).contains(marker));
			return buffer.toString(StandardCharsets.UTF_8);
		} finally {
			System.setOut(original);
		}
	}

	static void await(BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + 10_000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}

package aQute.launcher.spi;

import java.util.Map;

/**
 * Optional interface for an {@code Embedded-Activator} on the {@code -runpath}.
 * <p>
 * {@link #beforeFramework(Map, ClassLoader)} is called exactly once on the
 * launching thread, after the launcher has assembled the framework
 * configuration and before the {@code FrameworkFactory} is looked up, i.e.
 * before any framework class is initialised. Implementations may set the
 * thread context class loader and may add or change configuration entries.
 * Values may be objects; frameworks that only understand strings ignore them.
 * <p>
 * The normal {@code BundleActivator.start(BundleContext)} of the embedded
 * activator is called afterwards according to its {@code IMMEDIATE} phase.
 */
public interface LauncherExtension {

	/**
	 * @param configuration the framework configuration that will be passed to
	 *            {@code FrameworkFactory.newFramework}; mutable
	 * @param runpath the class loader of the {@code -runpath}, which also
	 *            loads the framework and this extension
	 */
	void beforeFramework(Map<String, Object> configuration, ClassLoader runpath);
}

# ServiceLoader in OSGi without mediator metadata

Plain `java.util.ServiceLoader` across OSGi bundles that carry **no** OSGi Service Loader Mediator metadata: no `osgi.serviceloader` capabilities or requirements, no `osgi.extender` requirement, no `module-info`. Providers have their `META-INF/services` files, consumers call `ServiceLoader.load(Greeter.class)`. Two mediators make that work, both run against the same example bundles and the same tests on Felix 7.0.5 and Equinox 3.23.0, and both are compared with Apache Aries SPI Fly.

| Variant | Module | Mechanism | Needs |
|---|---|---|---|
| **Weaver** | `org.example.spi.weaver` (Java 25), `org.example.spi.weaver.java21` | Framework extension with a `WeavingHook` that redirects `ServiceLoader.load` in the constant pool (or rewrites the call sites) | any launcher, a framework with framework extensions |
| **Launcher based mediator** | `org.example.spi.mediator` | `-runpath` jar that makes a registry aware class loader the thread context class loader and hooks the bundle class loaders of Felix and Equinox; nothing is woven | the extended bnd launcher `org.example.spi.launcher` |

Java 25 (plus a Java 21 variant of the weaver, see [The weaver](#the-weaver)), bnd 7.4.0 Maven plugins and tester, JUnit 5.14, OSGi Test 1.3, Maven wrapper included.

```bash
./mvnw clean install                                   # build everything, run all OSGi test runs and the benchmark
./mvnw install -DskipTests                             # build only
./mvnw -pl org.example.serviceloader.tests bnd-testing:testing -Dtesting='test-weaver*'   # one variant only
./mvnw -pl org.example.serviceloader.tests bnd-resolver:resolve                           # rewrite -runbundles
./mvnw -pl org.example.serviceloader.bench verify                                         # benchmark + probes, BENCH|/PROBE| lines in the log
```

## Modules

| Module | Content |
|---|---|
| `org.example.spi.core` | `SpiRegistry`, `SpiClassLoader`, `Tracing`. Shared by both mediators as an embedded private package, never deployed on its own. |
| `org.example.spi.weaver` | Framework extension bundle: `SpiWeaver` (`ExtensionBundle-Activator`), `ServiceLoaderWeavingHook`, `SpiLoaders`, exported `ServiceLoaders`. |
| `org.example.spi.weaver.java21` | The same sources and `bnd.bnd` built for Java 21, without `CallSiteWeaver` (constant pool technique only). |
| `org.example.spi.launcher` | A clone of bnd's launcher `biz.aQute.launcher` 7.4.0 (sources from Maven Central, unchanged apart from one addition) plus `aQute.launcher.spi.LauncherExtension`, a hook that runs before the framework is created. Replaces the built-in launcher when listed in `-runpath` (bnd takes the first runpath jar with a `Launcher-Plugin` header). |
| `org.example.spi.mediator` | `SpiMediator` (`Embedded-Activator` and `LauncherExtension`), `FelixAdapter`, `EquinoxAdapter`. |
| `org.example.spi.equinox` | Equinox only, and the smallest deployment of the three: `ServiceLoaderHookConfigurator` announced by a `hookconfigurators.properties` on the framework class path. No launcher, no weaving, not even a bundle. |
| `examples/*` | Greeter API 1.0 and 2.0 (same package name, two bundles, two class spaces), providers with `META-INF/services` only, consumers that call `ServiceLoader.load(Greeter.class)` in a DS `activate()`, one of them wired to the API with `Require-Bundle` instead of `Import-Package`. |
| `org.example.serviceloader.tests` | One test bundle, five bndruns: weaver and launcher based mediator, each on Felix and Equinox, plus the Equinox framework extension. |
| `org.example.serviceloader.bench` | Benchmark and capability probes under weaver, mediator and SPI Fly on both frameworks, plus a plain class path baseline. |

## Shared core

`SpiRegistry` tracks every bundle from RESOLVED to STOPPING (providers live as long as the bundle is resolved, like packages; a stopped bundle keeps them, an unresolved or uninstalled one loses them; the mediator never starts a bundle). Per bundle it reads `META-INF/services/*` of host and fragments plus `provides` clauses of a `module-info.class`. Every entry remembers the provider bundle, the source file and the `osgi.wiring.package` capability of the service type's package the provider is wired to. That capability is the class space key: a consumer gets exactly the providers wired to the same package capability as itself. The capability is looked up the way the framework searches for a class: `Import-Package` wire first, then `Require-Bundle` (which creates no package wire, so the export of the required bundle counts, transitively through `visibility:=reexport`), then the bundle's own export. `java.*` types are one class space for everybody, and a bundle that is wired to the API package in none of these ways keeps a private copy of it: no foreign provider can be type compatible, but its own providers are in its own class space and stay visible.

`SpiClassLoader` is the loader `java.util.ServiceLoader` talks to. `getResources("META-INF/services/<type>")` returns the real entry URLs of the provider bundles (a synthetic `spi:` URL only for providers declared solely in `module-info`), `loadClass` serves provider classes from their bundles, everything else goes to the parent. For a known consumer bundle the registry is the complete answer to a `META-INF/services` request, the consumer's own entries included, so the parent is not asked; on Equinox a resource miss in a bundle class loader costs about 13 µs (`BundleLoader.isRequestFromVM` stack walk plus compatibility boot delegation). Three modes: TCCL (consumer found with a `StackWalker`), bound (consumer known, used by the weaver), strict (one loader per bundle in front of Felix's boot delegation). Mediated is only what a `java.util.ServiceLoader` reads: `META-INF/services/<type>` is an ordinary resource, and a library with its own provider scanner gets the plain resources of the loader it asked. `ServiceLoaderCallers` decides that by looking for the JDK class that reads the provider configuration files on the stack; that class is not named but measured once at startup, by handing a probe class loader to a real `ServiceLoader` lookup, and if the measurement fails the guard stays open. The single resource form `getResource` is deliberately not guarded, because the `ServiceLoader` never calls it.

## The weaver

```
Fragment-Host: system.bundle; extension:=framework
ExtensionBundle-Activator: org.example.spi.weaver.SpiWeaver
Export-Package: org.example.spi.weaver;version=1.0.0
```

A weaving mediator must be active before the first consumer class is loaded. A framework extension is attached when it is installed, and Felix and Equinox run its activator right away with the system bundle context, before any regular bundle starts (the bnd launcher installs everything before it starts anything). No start level, no `osgi.extender` requirement. The exported package becomes an export of the system bundle; woven classes get a `DynamicImport-Package` on it.

The hook redirects the two static methods `ServiceLoader.load(Class)` and `load(Class, ClassLoader)` to `org.example.spi.weaver.ServiceLoaders`, with one of two techniques (`spi.weaver.technique`).

**`cpool`** (default) patches only the constant pool, without any bytecode library:

```
MethodRef -> Class java/util/ServiceLoader, load:(Ljava/lang/Class;)Ljava/util/ServiceLoader;
    ->  MethodRef -> Class org/example/spi/weaver/ServiceLoaders (appended), same NameAndType
```

The new `Utf8` and `Class` entries are appended, so every existing index stays valid: no instruction, stack map frame, descriptor or attribute changes, and the return type is still `java.util.ServiceLoader`. A method reference `ServiceLoader::load` is redirected with the call sites, because its `MethodHandle` constant points to the same `MethodRef`. `ServiceLoaders.load(Class)` finds the calling class as the first frame below it (hidden frames included: the lambda class of a method reference lives in the class loader of the class that holds the reference; `java.lang.invoke` frames are skipped). This is the targeted variant of the constant pool rewriting proposed by aicas (osgi/osgi#955): aicas renames the class `java/util/ServiceLoader` everywhere in the pool, so the woven class works with a proxy type that is not a `java.util.ServiceLoader` (a `NoSuchMethodError` as soon as the type crosses a class boundary, a re-implemented API without `stream()`); here only the two static entry points move, and the result is the real JDK `ServiceLoader`. Only the pool is parsed, whose format has not changed since Java 11; an unknown entry type leaves the class alone.

**`callsite`** rewrites the instructions with the Class-File API (`java.lang.classfile`, Java 24+, no ASM). It lives in `CallSiteWeaver`, which the hook loads by name only when this technique is configured and which is left out of the Java 21 variant (below); when it cannot be loaded, the hook falls back to `cpool` and traces why.

```
invokestatic java/util/ServiceLoader.load(Class)ServiceLoader
    ->  ldc ThisClass
        invokestatic org/example/spi/weaver/ServiceLoaders.load(Class, Class)ServiceLoader

invokestatic java/util/ServiceLoader.load(Class, ClassLoader)ServiceLoader
    ->  ldc ThisClass
        invokestatic org/example/spi/weaver/ServiceLoaders.load(Class, ClassLoader, Class)ServiceLoader
```

The calling class is pushed as a constant, so the consumer bundle is exact without any stack inspection. A method reference `ServiceLoader::load` has no call instruction; its `LambdaMetafactory` bootstrap argument is redirected to `ServiceLoaders.loadFrom(Class caller, ...)` and the calling class becomes the captured argument (call sites without other captures only). Stack maps are regenerated for changed methods.

**Java 21 variant.** `org.example.spi.weaver` is built for Java 25. `org.example.spi.weaver.java21` builds the same sources (copied without `CallSiteWeaver`) and the same `bnd.bnd` (`-include`) for Java 21, as a bundle of its own symbolic name, so both can sit in one repository; it always uses `cpool`. The core is built for Java 21 because both variants embed it (`StackWalker.Option.DROP_METHOD_INFO` is looked up by name and used from Java 22 on). `test-weaver-java21.bndrun` and `test-weaver-java21-equinox.bndrun` run all tests with the variant and request `callsite` on purpose to exercise the fallback. On a real Java 21 JVM (everything but the Java 25 weaver built with `-Dmaven.compiler.release=21`, tests run with a Java 21 `JAVA_HOME`) both runs pass as well.

Both techniques share two pre filters that keep the cost for the bulk of classes near zero: the raw bytes must contain `java/util/ServiceLoader`, the constant pool a `MethodRef` to `ServiceLoader.load`. The hook never throws, since a throwing `WeavingHook` is blacklisted; a failure leaves the class unwoven and is traced. All tests pass with either technique on Felix and Equinox.

`ServiceLoaders.load(type, caller)` returns `java.util.ServiceLoader.load(type, spiLoader)`, a real JDK `ServiceLoader` with a `SpiClassLoader` bound to the consumer bundle (cached per bundle wiring, all dropped when any bundle is unresolved, updated or uninstalled: the loader is the initiating loader of the provider classes it served, and the JVM would keep answering with the classes of a replaced provider) in front of the consumer's bundle class loader or the explicitly passed loader. Without an active registry or for a non-bundle caller the call falls back to the plain JDK call.

Properties: `spi.weaver.enabled` (default true), `spi.weaver.trace`, `spi.weaver.technique` (`cpool`, the default, or `callsite`), `spi.weaver.providerStates` (`resolved`, the default, or `active`), `spi.weaver.serviceLoaderOnly` (default true, see `ServiceLoaderCallers` above; set it to false to also serve a library that scans `META-INF/services` itself, such as Jersey's `ServiceFinder`), `spi.weaver.tccl` (default false): additionally make a TCCL mode `SpiClassLoader` the thread context class loader of the activating thread, which with the bnd launcher is the launching thread, inherited by every thread created afterwards. That is what makes bundle providers visible to the `ServiceLoader` calls inside the JDK (JAXP, StAX, ImageIO, JNDI) that cannot be woven, see [ServiceLoader calls inside the JDK](#serviceloader-calls-inside-the-jdk). Best effort: threads the framework created earlier keep their TCCL; the previous TCCL becomes the parent, so Equinox's `ContextFinder` stays in the chain.

## The launcher based mediator

No bytecode is touched. The jar sits on the bnd `-runpath` with an `Embedded-Activator` that also implements `LauncherExtension`. `beforeFramework(configuration, runpath)` runs on the launching thread before the `FrameworkFactory` is looked up: it creates the registry, makes a TCCL mode `SpiClassLoader` the thread context class loader (inherited by every framework and bundle thread; Equinox makes it the parent of its `ContextFinder`) and installs the framework hook for explicit `ServiceLoader.load(Class, bundleClassLoader)` calls: on Felix a `Map<Bundle, ClassLoader>` under `felix.bootdelegation.classloaders` that hands out a strict `SpiClassLoader` per bundle, on Equinox a `ClassLoaderHook` with `preFindResources`, `preFindResource` and `postFindClass`. `start(systemContext)` opens the registry before the first bundle is installed. Setting the TCCL before the framework exists and passing non-string values in the framework configuration are the two things a launcher must allow, and exactly what `LauncherExtension` adds to bnd's launcher (the copy in `org.example.spi.launcher` is bnd 7.4.0 plus that one interface and its call in `Launcher.activate()`).

Properties: `spi.mediator.enabled`, `spi.mediator.trace`, `spi.mediator.bundleLoaderHooks` (default true), `spi.mediator.providerStates`, `spi.mediator.serviceLoaderOnly` (default true, the same switch as for the weaver).

**The launcher is cloned from bnd.** `org.example.spi.launcher` is not our code: it is the source of `biz.aQute.launcher` 7.4.0 as published on Maven Central (packages `aQute.launcher`, `.constants`, `.minifw`, `.plugin`, `.pre`, `.agent`), built with the same bnd instructions as the original, and `biz.aQute.launcher.pre.jar` is taken unchanged from the original artifact. The only additions are the interface `aQute.launcher.spi.LauncherExtension` and about twenty lines in `Launcher.activate()` that instantiate the `Embedded-Activator`s before the framework is created and call `beforeFramework(configuration, runpath)` on those that implement the interface. bnd is licensed Apache-2.0 OR EPL-2.0; the clone keeps that license and the original headers. It exists only because bnd has no such hook yet; the intended end state is the same change upstream in bnd, after which this module disappears and the mediator runs with the standard launcher.

### How the caller is found: the StackWalker

Only the TCCL path needs to find out who is calling; the bundle loader hooks know the bundle, the weaver has it as a constant. The TCCL loader uses a `StackWalker` created once with `RETAIN_CLASS_REFERENCE` and, on Java 22 and newer, `DROP_METHOD_INFO` (no method names, line numbers or bytecode indices) and stops at the first frame whose class was loaded by a `BundleReference` class loader. The walk is lazy, so the cost depends on how deep the first bundle frame is, not on the stack depth. Measured on this machine with Java 25: about 0.9 µs when the first bundle frame is seven frames up (the real case), 3.6 µs for a 30 frame walk without a hit, 5.4 µs with full frame info. It happens once per `ServiceLoader.load` for the resource lookup and once per provider class name, never per instance or iteration, and never for anything that is not a ServiceLoader provider. Equinox's `ContextFinder` performs a `getClassContext()` walk for every class load through the TCCL, so one extra walk per lookup is in the noise of what the frameworks already do. Without a bundle frame on the stack the TCCL loader returns all providers unfiltered and traces a warning.

## Examples and what the tests check

The consumers print on activation:

```
[GreeterConsumer] org.example.serviceloader.provider.EnglishGreeter: Hello from the English greeter
[GreeterConsumer] org.example.serviceloader.provider.GermanGreeter: Hallo vom deutschen Greeter
[GreeterConsumer] found 2 Greeter provider(s) via java.util.ServiceLoader
[GreeterConsumerV2] org.example.serviceloader.provider.v2.FrenchGreeter (fr): Bonjour du greeter français
[GreeterConsumerV2] found 1 Greeter 2.0 provider(s) via java.util.ServiceLoader
```

- `GreeterServiceLoaderTest`: no bundle carries `osgi.serviceloader` or extender metadata; consumer 1.0 sees exactly the two 1.0 providers, consumer 2.0 exactly the 2.0 provider, although both API bundles export the same package name; `load(Class)`, `load(Class, bundleClassLoader)` and `stream()` from the test bundle; lifecycle like packages (stopped provider bundle keeps its providers and is not restarted, `update()` to INSTALLED removes them, `resolveBundles` brings them back, uninstall removes them).
- `JaxbServiceLoaderTest`: `jakarta.xml.bind-api` 4.0.2 and GlassFish runtime 4.0.5 from Maven Central, `JAXBContext.newInstance`, marshal and unmarshal.
- `WebSocketServiceLoaderTest`: `jakarta.websocket-client-api` 2.2.0 calls `ServiceLoader.load(ContainerProvider.class)`; Tyrus declares its provider only in `module-info`. The test sets the TCCL to the Tyrus bundle's loader because Tyrus loads its own container class by name through the TCCL, unrelated to `ServiceLoader`; under the launcher based mediator this lookup therefore runs through the bundle class loader hook.
- `JsonServiceLoaderTest`: `jakarta.json-api` 2.1.3 with Parsson 1.1.7 and `jakarta.json.bind-api` 3.0.0 with Yasson 3.0.4. Yasson's provider asks `JsonProvider.provider()` for its JSON-P implementation, a two level `ServiceLoader` chain through two API bundles. (`jakarta.json.bind-api` 3.0.1 ships an empty `Bundle-SymbolicName`, hence 3.0.0.)
- `RestAndPersistenceServiceLoaderTest`: `RuntimeDelegate.getInstance()`, `Response.ok().build()` and `UriBuilder` with Jersey 4.0.2 `jersey-common`; the Persistence provider list with EclipseLink 4.0.9. No server, no database, the lookups are the point.
- `MailAndValidationServiceLoaderTest`: `StreamProvider.provider()` and the `Session` transport and store providers with Angus Mail 2.0.3; a `ValidatorFactory` from Hibernate Validator 8.0.2 (with the `ParameterMessageInterpolator`, so no Expression Language is needed) validating a `@NotNull` violation.
- `Slf4jServiceLoaderTest`: slf4j 2.0.17 with `slf4j-simple`, the one API here that DOES carry Service Loader Mediator metadata, see [Bundles that bring their own metadata](#bundles-that-bring-their-own-metadata). `LoggerFactory` calls `ServiceLoader.load(SLF4JServiceProvider.class, LoggerFactory.class.getClassLoader())`, the two argument form with the API bundle's own class loader, from inside a third party bundle; the test checks the metadata, that both extender requirements are wired to the system bundle, that the same lookup from the test bundle finds `SimpleServiceProvider`, and that `LoggerFactory.getILoggerFactory()` really returns Simple's factory.
- `JdkFactoryServiceLoaderTest`: `XMLInputFactory.newInstance()` with Woodstox 7.1.1 installed as a bundle. The lookup runs inside the JDK; it passes under the launcher based mediator and under the weaver with `spi.weaver.tccl=true`.
- `JdkFactoryPitfallsTest`: the same kind of JDK internal lookup for StAX (Woodstox) and JAXP DOM and SAX (`org.example.serviceloader.jaxp.provider`, delegating to the JDK's built-in parsers, installed by the test), on the test thread, in the common pool and on a thread without TCCL. The weaver passes all of them through `JdkFactories`; the other deployments return the JDK default off the test thread.
- `ProviderClassNameClashTest` (osgi/osgi#970, Tom Watson's comment on the TCCL): two provider bundles ship a provider class of the same name for Greeter 1.0 and 2.0, two callers call `ServiceLoader.load(Class)` with the inherited TCCL or with their own bundle class loader as TCCL. A TCCL shared by all bundles is the JVM's initiating loader of the provider class: the second caller gets `not a subtype`, and after a reinstall the class of the uninstalled bundle keeps coming back. Passes with the weaver; the inherited TCCL cases fail under the other deployments by design, and so does the bundle class loader case after only the provider was replaced (the consumer's loader would need a refresh dependency on the provider).
- `TcclPitfallsTest`: a caller bundle wired to Greeter 2.0 (`org.example.serviceloader.pitfalls`, installed by the test) looks up `Greeter` in the ways that separate a call from its bundle: lambda, method reference, method reference applied by `Optional.map`, `MethodHandle`, reflection, explicit own and API class loader; and on threads with an unusual TCCL: common pool, no TCCL, a pool thread with a stale foreign TCCL, a foreign TCCL left on the thread. The weaver is exact everywhere except for `MethodHandle` and reflection off the test thread (not in the constant pool, so only the TCCL route is left); the other deployments are exact for every call form on the test thread and fail on every thread with a missing or foreign TCCL (nothing found, or the providers of the test bundle's class space).
- `WeaverTest` (skipped when the weaver is not installed): the extension is resolved and its package exported by the system bundle; consumer and test bundle carry a dynamic import wire to `org.example.spi.weaver` after their call sites ran, i.e. they were really woven; method references `ServiceLoader::load` with one and two parameters work; and the two `osgi.extender` capabilities the weaver declares are attached to the system bundle by both frameworks.

| bndrun | Framework | Mediator | Tests |
|---|---|---|---|
| `test-weaver` | Felix 7.0.5 | weaver (`spi.weaver.tccl=true`) | 31 pass |
| `test-weaver-equinox` | Equinox 3.23.0 | weaver (`spi.weaver.tccl=true`, `serviceLoaderOnly=false`) | 31 pass |
| `test-mediator` | Felix 7.0.5 | launcher based | 26 pass, 5 skipped |
| `test-mediator-equinox` | Equinox 3.23.0 | launcher based | 25 pass, 6 skipped |
| `test-equinox-extension` | Equinox 3.23.0 | framework extension, standard launcher | 25 pass, 6 skipped |

Skipped are the five `WeaverTest` cases wherever nothing is woven, and the guard test wherever no `spi-tccl` is installed (on Equinox the TCCL is the framework's own `ContextFinder`). Tracing is on everywhere (`spi.weaver.trace`, `spi.mediator.trace`, `spi.equinox.trace`); those lines show registry content, woven classes and every lookup.

`test-equinox-extension` is the answer to what a mediator needs from its host. On Equinox it needs nothing but the framework class path: the same 25 tests pass with the standard bnd launcher and no bytecode transformation, including the JDK factory lookups, because Equinox's own `ContextFinder` is the TCCL and resolves to a bundle class loader, which is exactly where the `ClassLoaderHook` sits. On Felix the same is impossible. `felix.bootdelegation.classloaders` is read from `m_configMap` (`BundleWiringImpl`), and that map is an unmodifiable copy of what was handed to the `Felix(Map)` constructor, so only the code that creates the framework can put the per bundle class loaders in; Felix has no hook registry and no file based equivalent. Without a launcher, Felix is left with what a framework extension activator can do on its own - set the TCCL - which covers `ServiceLoader.load(X)` and the JDK factory finders, but not `ServiceLoader.load(X, bundleClassLoader)`.

## Bundles that bring their own metadata

Everything above is about bundles that carry no Service Loader Mediator metadata. The opposite case has to work too, and it is not automatic: a bundle that follows the spec does not resolve unless someone provides the extender capability it asks for. slf4j 2 is the everyday example.

```
slf4j.api      Require-Capability: osgi.extender;filter:="(&(osgi.extender=osgi.serviceloader.processor)(version>=1.0.0)(!(version>=2.0.0)))",
                                   osgi.serviceloader;filter:="(osgi.serviceloader=org.slf4j.spi.SLF4JServiceProvider)"
slf4j.simple   Require-Capability: osgi.extender;filter:="(&(osgi.extender=osgi.serviceloader.registrar)(version>=1.0.0)(!(version>=2.0.0)))"
               Provide-Capability: osgi.serviceloader;osgi.serviceloader="org.slf4j.spi.SLF4JServiceProvider";register:="org.slf4j.simple.SimpleServiceProvider"
```

Both requirements are mandatory, so without a processor and a registrar in the system nothing resolves, the bundles never reach RESOLVED and no mediator ever gets the chance to do anything. The `osgi.serviceloader` requirement of the consumer is a different matter: it is satisfied by the provider bundle, exactly as the spec intends, and needs nothing from us.

The weaver declares both extender capabilities in its own manifest, and a framework extension's capabilities really do become capabilities of the system bundle: Felix 7.0.5 and Equinox 3.23.0 both attach them, which `WeaverTest` asserts by looking for capabilities on bundle 0 whose revision belongs to the weaver. At runtime the weaver is therefore a complete answer. The other two deployments are not bundles at all - a `-runpath` jar has no manifest the framework reads - so they cannot announce anything, and the launch configuration has to:

```
-runsystemcapabilities: \
	osgi.extender;osgi.extender=osgi.serviceloader.processor;version:Version=1.0.0,\
	osgi.extender;osgi.extender=osgi.serviceloader.registrar;version:Version=1.0.0
```

All five bndruns carry that, the weaver runs included, because of the resolver rather than the runtime: to bnd's resolver the weaver is an ordinary resource that provides `osgi.extender`, and left to itself it satisfies slf4j's requirement by adding `org.example.spi.weaver` to the `-runbundles` of the runs that use one of the other two mediators. Declaring the capabilities on the system bundle keeps each run with the mediator it is meant to test.

Honesty about the registrar: claiming `osgi.serviceloader.registrar` here is structural. Providers become visible to `java.util.ServiceLoader`, which is what slf4j and every library like it actually does, but they are not registered as OSGi services the way spec chapter 133 describes and SPI Fly implements. Nothing in this test bed looks for those services. Registering them would be a small addition to the registry - it already knows every provider, its bundle and its class space - and it is the one piece that would make the claim literal.

What the mediators then have to serve is worth spelling out, because it is the hardest of the lookup forms: `LoggerFactory.findServiceProviders()` calls `ServiceLoader.load(SLF4JServiceProvider.class, LoggerFactory.class.getClassLoader())`, the two argument form with the API bundle's own class loader, from inside a third party bundle. The weaver rewrites the call site in `LoggerFactory` itself (two of them: the plain call and the one in the `doPrivileged` lambda). The launcher based mediator and the Equinox extension never see a call site; they answer because the class loader that is passed is a bundle class loader they have hooked. All five runs bind `org.slf4j.simple.SimpleServiceProvider`.

## Search paths of the Jakarta APIs

The question behind the API tests is not whether one `ServiceLoader` call works but where the Jakarta APIs look for their implementation and which step a mediator can serve. From the API sources on Maven Central; every row is covered by a test in `org.example.serviceloader.tests` with the implementation named in the last column, installed as it comes from Maven Central:

| API and entry point | Lookup order in the API | Step served | Works in OSGi without a mediator? | Tested with |
|---|---|---|---|---|
| WebSocket 2.2 (`jakarta.websocket-client-api` 2.2.0) `ContainerProvider.getWebSocketContainer()` | 1. `ServiceLoader.load(ContainerProvider.class)` (TCCL), nothing else | 1 | no | Tyrus 2.2.0 (provider only in `module-info`) |
| JAXB 4.0 (`jakarta.xml.bind-api` 4.0.2) `JAXBContext.newInstance(...)` | 1. system property 2. `jaxb.properties` in the context path 3. `ServiceLoader.load(JAXBContextFactory.class)` (TCCL) 4. HK2 `osgiresourcelocator` if present 5. default class by `Class.forName` | 3 | only with a system property, `jaxb.properties` or HK2; step 5 fails, the API bundle does not import the runtime package | GlassFish `jaxb-runtime` 4.0.5 |
| JSON-P 2.1 (`jakarta.json-api` 2.1.3) `JsonProvider.provider()` | 1. system property 2. `ServiceLoader.load(JsonProvider.class)` (TCCL) 3. HK2 4. default class `org.eclipse.parsson.JsonProviderImpl` | 2 | only with the property, HK2 or Parsson's combined API plus implementation bundle | Parsson 1.1.7 |
| JSON-B 3.0 (`jakarta.json.bind-api` 3.0.0) `JsonbProvider.provider()` | 1. `ServiceLoader.load(JsonbProvider.class)` (TCCL) 2. default class `org.eclipse.yasson.JsonBindingProvider` | 1, plus Yasson's own JSON-P lookup | no | Yasson 3.0.4 |
| REST 4.0 (`jakarta.ws.rs-api` 4.0.0) `RuntimeDelegate.getInstance()` (`FactoryFinder`, same order as 3.1) | 1. `$java.home/lib/jaxrs.properties` 2. system property 3. `ServiceLoader.load(service, TCCL)` 4. default class (Jersey) | 3 | only with the properties | Jersey `jersey-common` 4.0.2 |
| Bean Validation 3.0 (`jakarta.validation-api` 3.0.2) `Validation.byDefaultProvider().configure()` | 1. `ServiceLoader.load(ValidationProvider.class, TCCL)` | 1 | no | Hibernate Validator 8.0.2 |
| Persistence 3.1 (`jakarta.persistence-api` 3.1.0) `PersistenceProviderResolverHolder` | 1. `ServiceLoader.load(PersistenceProvider.class, TCCL)` via a replaceable resolver | 1 | only through a custom resolver | EclipseLink 4.0.9 |
| Mail 2.1 (`jakarta.mail-api` 2.1.3) `StreamProvider.provider()`, `Session` providers | 1. system property 2. `ServiceLoader.load(factoryClass, TCCL)` 3. HK2 4. default class | 2 | only with a property or HK2 | Angus Mail 2.0.3 |

Every API has a `ServiceLoader` step, and in most of them it is the only step that works inside OSGi without static configuration: the default class names are resolved by `Class.forName` from the API bundle, which does not import the implementation package. All these calls happen inside the API bundle, so the API bundle is the consumer, and both mediators filter by its own package capability, exactly the class space an implementation must be wired to. The `load(Class, ClassLoader)` variants pass the TCCL, which is the `SpiClassLoader` on Felix and the `ContextFinder` on Equinox; both end in the registry. The HK2 `osgiresourcelocator` is the pre-R6 way, a separate bundle reached through a `DynamicImport-Package`; it is not part of any run here.

### ServiceLoader calls inside the JDK

JDK factory lookups (JAXP, StAX, JNDI, ImageIO, `ScriptEngineManager`, `DriverManager`) cannot be woven, and most of what people need already works in OSGi without any mediator. The service types are boot types, one class space for everybody, and the JDK finders check a system property and a properties file before `ServiceLoader`, so:

- The **JDK default** works everywhere. `java.xml` declares no `provides`; the defaults are hard coded fallback class names used when `ServiceLoader` returns nothing, and `newDefaultFactory()`/`newDefaultInstance()` (Java 9+) return them directly.
- A **provider jar on the JVM class path** (bnd: the `-runpath`) works without a mediator. The launching thread's TCCL is the application class loader and Equinox's `ContextFinder` has it as parent, so the JDK's `ServiceLoader.load(type)` with the TCCL finds the jar's `META-INF/services` through parent delegation, the same way it does in plain Java. This is also the only way for the JDK SPIs that use the system class loader (`System.LoggerFinder`, `URLStreamHandlerProvider`, `CharsetProvider`, `ZoneRulesProvider`, `ToolProvider`, `java.security.Provider`). The provider is then not a bundle: no lifecycle, no wiring, one copy per JVM, which is fine for boot types.
- The **system property** (`javax.xml.stream.XMLInputFactory=...`) selects an implementation explicitly and stays first in every setup, mediator or not.

The one use case that needs a mediator is a **provider that is a bundle**, Woodstox installed as `com.fasterxml.woodstox.woodstox-core` rather than dropped on the class path: bundles are invisible to the application class loader, so the JDK's TCCL based lookup never sees their `META-INF/services`. Both mediators fix that with the same means, a registry aware TCCL: the launcher based mediator sets it before the framework exists, the weaver with `spi.weaver.tccl=true`. Verified with `JdkFactoryServiceLoaderTest` and the benchmark probe: `XMLInputFactory.newInstance()` returns Woodstox's `WstxInputFactory` under both, on Felix and Equinox, and the JDK's `XMLInputFactoryImpl` under the weaver without the option and under SPI Fly (whose auto mode weaves bundle call sites only; an `SPI-Consumer: <class>#<method>()` header would be needed). The semantics are exactly those of the class path: the first provider found wins, the property overrides, the registry never has to know the JDK's own providers because `java.util.ServiceLoader` reads the module layer catalogs itself. Limits of the TCCL route: threads the framework created before the mediator keep their old TCCL, and code running with a foreign or missing TCCL (common pool, a thread without TCCL, a pool thread with a stale TCCL) is not reached. The weaver therefore also redirects the StAX and JAXP factory calls of bundle classes (`XMLInputFactory`, `XMLOutputFactory`, `XMLEventFactory`: `newInstance()`, `newFactory()`; `DocumentBuilderFactory`, `SAXParserFactory`: `newInstance()`, `newNSInstance()`; `TransformerFactory.newInstance()`; `XPathFactory.newInstance()` and `newInstance(String)`; `SchemaFactory.newInstance(String)`; `DatatypeFactory.newInstance()`) in the constant pool to `org.example.spi.weaver.JdkFactories`, which runs the original call with the calling bundle's `SpiClassLoader` as TCCL, SPI Fly's second weaving mode without a header. That is exact on any thread; the variants with an explicit class loader are left alone, and JNDI (`new InitialContext()`, a constructor) is not covered. Bundle providers cannot be made visible to the system class loader group at all.

## Weaver vs. launcher based mediator

- **Weaver, pro:** any launcher, any framework with framework extensions, one bundle, nothing to configure. Exact caller (a constant with `callsite`, the frame directly below with `cpool`), no TCCL involved, immune to code running with a foreign TCCL. Fastest on the common path; the returned `ServiceLoader` keeps working after the call (`stream()`, `iterator()`, `reload()`). No third party dependency, no ASM version to chase.
- **Weaver, con:** bytecode changes at class load time (with `cpool` two constant pool entries, with `callsite` two call patterns plus method reference bootstrap arguments). Only call sites in bundle classes are covered; bundle providers for JDK internal lookups need the `spi.weaver.tccl` option, which is best effort. With `callsite`, method references with captured arguments stay unwoven (traced); that technique needs Java 24+, the Java 21 variant of the weaver has `cpool` only.
- **Launcher based mediator, pro:** no bytecode touched, stack traces and signatures untouched. Covers every `ServiceLoader` use that ends in `getResources`/`loadClass`: direct calls, method references, JDK internals, generated code, explicit bundle class loaders. The only one of the three that handles all probes.
- **Launcher based mediator, con:** needs the extended bnd launcher until bnd has `LauncherExtension` upstream. One adapter per framework for the bundle class loader path. `StackWalker` on the TCCL path (about 1 µs); a thread with a foreign TCCL bypasses that path. On Equinox the TCCL path pays for the `ContextFinder`, the slowest measured mediated path.
- **Rule of thumb:** weaver by default; launcher based mediator when bytecode must stay untouched or `ServiceLoader` is used from places the weaver cannot reach. They share the registry implementation, not an instance.

## Comparison with Apache Aries SPI Fly

SPI Fly is the reference implementation of the OSGi Service Loader Mediator specification. Its ASM visitor rewrites `ServiceLoader.load(Class)` to `Util.serviceLoaderLoad(Class, Class)` with the woven class as a constant, exactly what the weaver here does with the Class-File API; for other factory methods it wraps the call in a TCCL switch with a generated helper. It never inspects the stack. Providers and consumers come from metadata (`SPI-Provider`/`SPI-Consumer` headers, `osgi.serviceloader` capabilities and requirements, `osgi.extender` for ordering) or, since 1.3, from the framework properties `org.apache.aries.spifly.auto.consumers` and `auto.providers` (symbolic name globs, matched as substrings), which the benchmark uses. There is no notion of class spaces in any mode. For `load(Class)` SPI Fly switches the TCCL to the provider loaders for the duration of the call and invokes the plain `ServiceLoader.load`; for `load(Class, ClassLoader)` it wraps the given loader in a `WrapperCL` created per call.

| | SPI Fly | Weaver here | Launcher based mediator here |
|---|---|---|---|
| Caller | woven class constant | frame below `ServiceLoaders` (`cpool`) or woven class constant (`callsite`) | bundle loader hooks, `StackWalker` on the TCCL path |
| Provider discovery | `SPI-Provider` / `osgi.serviceloader` capability / `auto.providers` | `META-INF/services` and `module-info` of every resolved bundle | same |
| Consumer filter | `SPI-Consumer` / `osgi.serviceloader` requirement / `auto.consumers` | class space via package capability | same |
| Start ordering | `osgi.extender` requirement, or framework extension | framework extension | runs before the framework |
| Byte code engine | ASM (framework extension 1.3.7 embeds one that stops at Java 22 class files; the dynamic bundle takes an external ASM, 9.8 reads Java 25, 9.10 Java 27) | none, constant pool patch (`cpool`), or `java.lang.classfile` (`callsite`) | none |
| Result of `load(Class)` | plain `ServiceLoader` under a temporarily switched TCCL | `ServiceLoader` with a bound `SpiClassLoader` | plain `ServiceLoader`, TCCL is the `SpiClassLoader` |

## Where each one works

Observed with the probes in `org.example.serviceloader.bench` (`PROBE|` lines) unless marked "by design". SPI Fly 1.3.7 as dynamic bundle with ASM 9.10.1 and `auto.consumers=org.example.serviceloader.bench`, `auto.providers=org.example.serviceloader.provider*`.

How to read the cells: the probe bundle is wired to Greeter 1.0; installed are the 1.0 provider bundle with `EnglishGreeter` and `GermanGreeter` and, for the class space row, the 2.0 provider bundle with `FrenchGreeter`. **2 of 2** means exactly the two 1.0 providers came back, **0 of 2** means the mediator did not take part in the lookup (plain JDK behaviour), an error text means the lookup broke.

| Situation | Weaver | Launcher based mediator | SPI Fly |
|---|---|---|---|
| Consumer bundle without any metadata | yes | yes | only if listed in `auto.consumers`, else `SPI-Consumer` or `osgi.serviceloader`/`osgi.extender` requirements |
| Provider bundle with `META-INF/services` only | yes | yes | only if listed in `auto.providers`, else `SPI-Provider` or `osgi.serviceloader` capability |
| Provider declared only in `module-info` (Tyrus) | yes | yes | no (by design) |
| Bundle that carries the spec metadata and requires the extender (slf4j 2) | yes, the framework extension declares `osgi.extender=osgi.serviceloader.processor` and `...registrar` itself | yes, but the extender capabilities have to come from `-runsystemcapabilities` | yes (by design) |
| `load(Class)` direct, in a lambda, in a nested class; `load(Class, ClassLoader)` | 2 of 2 | 2 of 2 | 2 of 2 |
| Method reference `ServiceLoader::load` | 2 of 2 (method handle constant redirected with the pool entry, or bootstrap argument redirected) | 2 of 2 | **0 of 2** (rewrites call instructions only) |
| `ServiceLoader` inside a library bundle (jakarta.xml.bind, websocket-client) | yes | yes | if the library carries metadata or is listed in `auto.consumers` |
| Bundle provider for a `ServiceLoader` call inside the JDK (`XMLInputFactory.newInstance()`, Woodstox as bundle) | JDK default; **Woodstox with `spi.weaver.tccl=true`** | Woodstox | JDK default; only an `SPI-Consumer: <class>#<method>()` header would help |
| Two versions of the API package installed, consumer wired to 1.0 | 2 of 2 | 2 of 2 | **`ServiceConfigurationError: FrenchGreeter not a subtype`** on every lookup |
| Provider bundle stopped (RESOLVED) | 2 of 2, like packages | 2 of 2 | 0 of 2, tracks STARTING and ACTIVE only; 2 of 2 again after start |
| Providers also registered as OSGi services | no | no | yes (by design) |
| Consumer bytecode | changed at load time | untouched | changed at load time, or at build time with the static tool |
| Launcher / framework | any / any with framework extensions | extended bnd launcher / Felix and Equinox | any / any with weaving hooks |
| Java class file version of consumers | any with `cpool`, whatever the running JDK reads with `callsite` | irrelevant | bound to the ASM version |

The class space row is the reason this project exists: without the package wiring as filter, a second API version in the framework breaks every consumer of the first one, not just the one that asked. The filter also bites the other way round when it should: in the bnd workspace, whose repository holds `jakarta.ws.rs-api` 3.1 and 4.0, the resolver once wired Jersey 4 to the 3.1 API while the test imported 4.0, and the REST lookup correctly found no provider until the 3.1 API was blacklisted.

## Performance

Setup: `org.example.serviceloader.bench`, Java 25, one developer machine, 200 000 measured operations after 50 000 warm up operations, two providers of Greeter 1.0, a fresh `ServiceLoader` per operation (how application code uses it and where a mediator adds its cost). Best of two runs after a clean build. The plain column is the same loop on the flat surefire class path without OSGi. All values are **microseconds per operation**.

**Felix 7.0.5**

| Scenario | plain class path (µs/op) | Weaver (µs/op) | Launcher based mediator (µs/op) | SPI Fly, auto properties (µs/op) |
|---|---|---|---|---|
| `load(Class)` only, no iteration | 0.04 | 0.08 | 0.07 | 2.7 |
| `load(Class)` + iterate 2 providers | 15.8 | 7.7 | 7.0 | 10.9 |
| `load(Class)` + `stream()` types only | 12.0 | 4.5 | 12.0 | 15.3 |
| `load(Class, bundleClassLoader)` + iterate | 21.0 | 7.3 | 5.6 | 76 |

**Equinox 3.23.0**

| Scenario | plain class path (µs/op) | Weaver (µs/op) | Launcher based mediator (µs/op) | SPI Fly, auto properties (µs/op) |
|---|---|---|---|---|
| `load(Class)` only, no iteration | 0.04 | 0.09 | 0.05 | 5.6 |
| `load(Class)` + iterate 2 providers | 15.8 | 5.4 | 21.2 | 8.6 |
| `load(Class)` + `stream()` types only | 12.0 | 4.2 | 20.7 | 8.3 |
| `load(Class, bundleClassLoader)` + iterate | 21.0 | 7.4 | 7.8 | 77 |

- **The JDK dominates.** Every `ServiceLoader` instance re-reads and parses the `META-INF/services` resources and calls `Class.forName` per provider; the mediators add microseconds, with two exceptions on the SPI Fly side.
- **`load(Class)` alone is nearly free** for weaver and mediator, the JDK `ServiceLoader` is lazy. SPI Fly resolves providers eagerly at that point.
- **Weaver and mediator are equal on Felix** within the noise; the `StackWalker` is worth about 1 µs and does not show above it.
- **Equinox makes the mediator's TCCL path expensive**: the TCCL is the `ContextFinder`, which walks the stack itself, asks the bundle loader and then its parent, the mediator's TCCL loader with its own walk. The explicit class loader path through the `ClassLoaderHook` costs the same as on Felix. The weaver never touches the TCCL.
- **SPI Fly `load(Class, ClassLoader)`** creates a `WrapperCL` per call with a double lookup, hence 76 µs.
- **The flat class path is slow** because the application class loader scans every jar on the surefire class path per operation; in OSGi the registry hands over exactly the two provider entries.
- **Noise:** 10 to 50 percent per cell between runs; differences below about 3 µs between two OSGi cells are not a ranking. Stable across all runs: the weaver is fastest or tied in every OSGi scenario, SPI Fly's eager `load` and `WrapperCL` path are an order of magnitude apart, the mediator's TCCL path on Equinox is the slowest mediated path.

## Maven notes

- `${revision}` as CI friendly version, `flatten-maven-plugin` writes resolved poms on install.
- Example, core and test modules have no `bnd.bnd`; the bnd-maven-plugin infers symbolic name, version, description, DS components and exports (`package-info.java`). Only modules with real bnd instructions (weaver, launcher, mediator, test bundles' `Test-Cases`) carry one.
- In a bndrun, `-runpath` entries need version ranges. `version=latest` makes bnd look for a workspace project of that name first, which in a Maven build is a non-existent `<module>/generated/<name>.jar`.
- `biz.aQute.tester.junit-platform` adds only itself; `junit-platform-launcher` and `junit-jupiter-engine` are listed in `-runrequires`.
- `-runbundles` are checked in; run the resolver goal after dependency changes.
- **Embedded packages need `clean`.** The bnd-maven-plugin expands the built bundle into `target/classes`, so the weaver's and mediator's output directories hold a copy of `org.example.spi.core` that shadows the rebuilt core module on an incremental build. Core changes reach the mediators only with `./mvnw clean install`.
- SPI Fly's `auto.*` globs are substring matches; `bench-spifly.bndrun` leaves the Greeter 2.0 bundles out, `bench-spifly-classspace.bndrun` installs them on purpose.
- The launcher module unpacks `biz.aQute.launcher.pre.jar` from the original launcher artifact into its output directory.

## Background

The reference for this work is the aicas [osgi-service-loader](https://github.com/aicas/osgi-service-loader) project (ASM weaving, `module-info` support); Apache Aries SPI Fly is the reference implementation of the specification this project deliberately does without. The launcher module is a clone of [bnd](https://github.com/bndtools/bnd)'s `biz.aQute.launcher` 7.4.0 (Apache-2.0 OR EPL-2.0) with one added hook, see [The launcher based mediator](#the-launcher-based-mediator). Everything else in this repository is EPL-2.0.

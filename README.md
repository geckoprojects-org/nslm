# ServiceLoader in OSGi without mediator metadata

Plain `java.util.ServiceLoader` across OSGi bundles that carry **no** OSGi Service Loader Mediator metadata: no `osgi.serviceloader` capabilities or requirements, no `osgi.extender` requirement, no `module-info`. Providers have their `META-INF/services` files, consumers call `ServiceLoader.load(Greeter.class)`. Two mediators make that work, both run against the same example bundles and the same tests on Felix 7.0.5 and Equinox 3.23.0, and both are compared with Apache Aries SPI Fly.

| Variant | Module | Mechanism | Needs |
|---|---|---|---|
| **Weaver** | `org.example.spi.weaver` | Framework extension with a `WeavingHook` (Class-File API) that redirects `ServiceLoader.load` call sites | any launcher, a framework with framework extensions |
| **Launcher based mediator** | `org.example.spi.mediator` | `-runpath` jar that makes a registry aware class loader the thread context class loader and hooks the bundle class loaders of Felix and Equinox; nothing is woven | the extended bnd launcher `org.example.spi.launcher` |

Java 25, bnd 7.4.0 Maven plugins and tester, JUnit 5.14, OSGi Test 1.3, Maven wrapper included.

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
| `org.example.spi.launcher` | A clone of bnd's launcher `biz.aQute.launcher` 7.4.0 (sources from Maven Central, unchanged apart from one addition) plus `aQute.launcher.spi.LauncherExtension`, a hook that runs before the framework is created. Replaces the built-in launcher when listed in `-runpath` (bnd takes the first runpath jar with a `Launcher-Plugin` header). |
| `org.example.spi.mediator` | `SpiMediator` (`Embedded-Activator` and `LauncherExtension`), `FelixAdapter`, `EquinoxAdapter`. |
| `examples/*` | Greeter API 1.0 and 2.0 (same package name, two bundles, two class spaces), providers with `META-INF/services` only, consumers that call `ServiceLoader.load(Greeter.class)` in a DS `activate()`. |
| `org.example.serviceloader.tests` | One test bundle, four bndruns: weaver and launcher based mediator, each on Felix and Equinox. |
| `org.example.serviceloader.bench` | Benchmark and capability probes under weaver, mediator and SPI Fly on both frameworks, plus a plain class path baseline. |

## Shared core

`SpiRegistry` tracks every bundle from RESOLVED to STOPPING (providers live as long as the bundle is resolved, like packages; a stopped bundle keeps them, an unresolved or uninstalled one loses them; the mediator never starts a bundle). Per bundle it reads `META-INF/services/*` of host and fragments plus `provides` clauses of a `module-info.class`. Every entry remembers the provider bundle, the source file and the `osgi.wiring.package` capability of the service type's package the provider is wired to. That capability is the class space key: a consumer gets exactly the providers wired to the same package capability as itself, a consumer not wired to the API package gets nothing, `java.*` types are one class space for everybody.

`SpiClassLoader` is the loader `java.util.ServiceLoader` talks to. `getResources("META-INF/services/<type>")` returns the real entry URLs of the provider bundles (a synthetic `spi:` URL only for providers declared solely in `module-info`), `loadClass` serves provider classes from their bundles, everything else goes to the parent. For a known consumer bundle the registry is the complete answer to a `META-INF/services` request, the consumer's own entries included, so the parent is not asked; on Equinox a resource miss in a bundle class loader costs about 13 µs (`BundleLoader.isRequestFromVM` stack walk plus compatibility boot delegation). Three modes: TCCL (consumer found with a `StackWalker`), bound (consumer known, used by the weaver), strict (one loader per bundle in front of Felix's boot delegation).

## The weaver

```
Fragment-Host: system.bundle; extension:=framework
ExtensionBundle-Activator: org.example.spi.weaver.SpiWeaver
Export-Package: org.example.spi.weaver;version=1.0.0
```

A weaving mediator must be active before the first consumer class is loaded. A framework extension is attached when it is installed, and Felix and Equinox run its activator right away with the system bundle context, before any regular bundle starts (the bnd launcher installs everything before it starts anything). No start level, no `osgi.extender` requirement. The exported package becomes an export of the system bundle; woven classes get a `DynamicImport-Package` on it.

The hook rewrites exactly two call sites with the Class-File API (`java.lang.classfile`, Java 24+, no ASM):

```
invokestatic java/util/ServiceLoader.load(Class)ServiceLoader
    ->  ldc ThisClass
        invokestatic org/example/spi/weaver/ServiceLoaders.load(Class, Class)ServiceLoader

invokestatic java/util/ServiceLoader.load(Class, ClassLoader)ServiceLoader
    ->  ldc ThisClass
        invokestatic org/example/spi/weaver/ServiceLoaders.load(Class, ClassLoader, Class)ServiceLoader
```

The calling class is pushed as a constant, so the consumer bundle is exact without any stack inspection. A method reference `ServiceLoader::load` has no call instruction; its `LambdaMetafactory` bootstrap argument is redirected to `ServiceLoaders.loadFrom(Class caller, ...)` and the calling class becomes the captured argument (call sites without other captures only). Two pre filters keep the cost for the bulk of classes near zero: the raw bytes must contain `java/util/ServiceLoader`, the constant pool a `MethodRef` to `ServiceLoader.load`. Stack maps are regenerated for changed methods only. The hook never throws, since a throwing `WeavingHook` is blacklisted; a failure leaves the class unwoven and is traced.

`ServiceLoaders.load(type, caller)` returns `java.util.ServiceLoader.load(type, spiLoader)`, a real JDK `ServiceLoader` with a `SpiClassLoader` bound to the consumer bundle (cached per bundle wiring) in front of the consumer's bundle class loader or the explicitly passed loader. Without an active registry or for a non-bundle caller the call falls back to the plain JDK call.

Properties: `spi.weaver.enabled` (default true), `spi.weaver.trace`, `spi.weaver.providerStates` (`resolved`, the default, or `active`), `spi.weaver.tccl` (default false): additionally make a TCCL mode `SpiClassLoader` the thread context class loader of the activating thread, which with the bnd launcher is the launching thread, inherited by every thread created afterwards. That is what makes bundle providers visible to the `ServiceLoader` calls inside the JDK (JAXP, StAX, ImageIO, JNDI) that cannot be woven, see [ServiceLoader calls inside the JDK](#serviceloader-calls-inside-the-jdk). Best effort: threads the framework created earlier keep their TCCL; the previous TCCL becomes the parent, so Equinox's `ContextFinder` stays in the chain.

## The launcher based mediator

No bytecode is touched. The jar sits on the bnd `-runpath` with an `Embedded-Activator` that also implements `LauncherExtension`. `beforeFramework(configuration, runpath)` runs on the launching thread before the `FrameworkFactory` is looked up: it creates the registry, makes a TCCL mode `SpiClassLoader` the thread context class loader (inherited by every framework and bundle thread; Equinox makes it the parent of its `ContextFinder`) and installs the framework hook for explicit `ServiceLoader.load(Class, bundleClassLoader)` calls: on Felix a `Map<Bundle, ClassLoader>` under `felix.bootdelegation.classloaders` that hands out a strict `SpiClassLoader` per bundle, on Equinox a `ClassLoaderHook` with `preFindResources`, `preFindResource` and `postFindClass`. `start(systemContext)` opens the registry before the first bundle is installed. Setting the TCCL before the framework exists and passing non-string values in the framework configuration are the two things a launcher must allow, and exactly what `LauncherExtension` adds to bnd's launcher (the copy in `org.example.spi.launcher` is bnd 7.4.0 plus that one interface and its call in `Launcher.activate()`).

Properties: `spi.mediator.enabled`, `spi.mediator.trace`, `spi.mediator.bundleLoaderHooks` (default true), `spi.mediator.providerStates`.

**The launcher is cloned from bnd.** `org.example.spi.launcher` is not our code: it is the source of `biz.aQute.launcher` 7.4.0 as published on Maven Central (packages `aQute.launcher`, `.constants`, `.minifw`, `.plugin`, `.pre`, `.agent`), built with the same bnd instructions as the original, and `biz.aQute.launcher.pre.jar` is taken unchanged from the original artifact. The only additions are the interface `aQute.launcher.spi.LauncherExtension` and about twenty lines in `Launcher.activate()` that instantiate the `Embedded-Activator`s before the framework is created and call `beforeFramework(configuration, runpath)` on those that implement the interface. bnd is licensed Apache-2.0 OR EPL-2.0; the clone keeps that license and the original headers. It exists only because bnd has no such hook yet; the intended end state is the same change upstream in bnd, after which this module disappears and the mediator runs with the standard launcher.

### How the caller is found: the StackWalker

Only the TCCL path needs to find out who is calling; the bundle loader hooks know the bundle, the weaver has it as a constant. The TCCL loader uses a `StackWalker` created once with `RETAIN_CLASS_REFERENCE` and `DROP_METHOD_INFO` (no method names, line numbers or bytecode indices) and stops at the first frame whose class was loaded by a `BundleReference` class loader. The walk is lazy, so the cost depends on how deep the first bundle frame is, not on the stack depth. Measured on this machine with Java 25: about 0.9 µs when the first bundle frame is seven frames up (the real case), 3.6 µs for a 30 frame walk without a hit, 5.4 µs with full frame info. It happens once per `ServiceLoader.load` for the resource lookup and once per provider class name, never per instance or iteration, and never for anything that is not a ServiceLoader provider. Equinox's `ContextFinder` performs a `getClassContext()` walk for every class load through the TCCL, so one extra walk per lookup is in the noise of what the frameworks already do. Without a bundle frame on the stack the TCCL loader returns all providers unfiltered and traces a warning.

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
- `JdkFactoryServiceLoaderTest`: `XMLInputFactory.newInstance()` with Woodstox 7.1.1 installed as a bundle. The lookup runs inside the JDK; it passes under the launcher based mediator and under the weaver with `spi.weaver.tccl=true`.
- `WeaverTest` (skipped when the weaver is not installed): the extension is resolved and its package exported by the system bundle; consumer and test bundle carry a dynamic import wire to `org.example.spi.weaver` after their call sites ran, i.e. they were really woven; method references `ServiceLoader::load` with one and two parameters work.

| bndrun | Framework | Mediator | Tests |
|---|---|---|---|
| `test-weaver` | Felix 7.0.5 | weaver (`spi.weaver.tccl=true`) | 22 pass |
| `test-weaver-equinox` | Equinox 3.23.0 | weaver (`spi.weaver.tccl=true`) | 22 pass |
| `test-mediator` | Felix 7.0.5 | launcher based | 18 pass, 4 skipped (`WeaverTest`) |
| `test-mediator-equinox` | Equinox 3.23.0 | launcher based | 18 pass, 4 skipped (`WeaverTest`) |

Tracing is on in all four bndruns (`spi.weaver.trace=true`, `spi.mediator.trace=true`); the `# spi.weaver:` and `# spi.mediator:` lines show registry content, woven classes and every lookup.

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

The one use case that needs a mediator is a **provider that is a bundle**, Woodstox installed as `com.fasterxml.woodstox.woodstox-core` rather than dropped on the class path: bundles are invisible to the application class loader, so the JDK's TCCL based lookup never sees their `META-INF/services`. Both mediators fix that with the same means, a registry aware TCCL: the launcher based mediator sets it before the framework exists, the weaver with `spi.weaver.tccl=true`. Verified with `JdkFactoryServiceLoaderTest` and the benchmark probe: `XMLInputFactory.newInstance()` returns Woodstox's `WstxInputFactory` under both, on Felix and Equinox, and the JDK's `XMLInputFactoryImpl` under the weaver without the option and under SPI Fly (whose auto mode weaves bundle call sites only; an `SPI-Consumer: <class>#<method>()` header would be needed). The semantics are exactly those of the class path: the first provider found wins, the property overrides, the registry never has to know the JDK's own providers because `java.util.ServiceLoader` reads the module layer catalogs itself. Limits of the TCCL route: threads the framework created before the mediator keep their old TCCL, and code running with a foreign TCCL is not reached; SPI Fly's second weaving mode (wrapping the bundle's factory call in a TCCL switch, possible with `CodeBuilder.trying`) would stay exact there and is not implemented here. Bundle providers cannot be made visible to the system class loader group at all.

## Weaver vs. launcher based mediator

- **Weaver, pro:** any launcher, any framework with framework extensions, one bundle, nothing to configure. Exact caller from a constant, no TCCL involved, immune to code running with a foreign TCCL. Fastest on the common path; the returned `ServiceLoader` keeps working after the call (`stream()`, `iterator()`, `reload()`). No third party dependency, no ASM version to chase.
- **Weaver, con:** bytecode changes at class load time (two call patterns plus method reference bootstrap arguments). Only call sites in bundle classes are covered; bundle providers for JDK internal lookups need the `spi.weaver.tccl` option, which is best effort. Method references with captured arguments stay unwoven (traced). Needs Java 24 or newer for the weaver itself.
- **Launcher based mediator, pro:** no bytecode touched, stack traces and signatures untouched. Covers every `ServiceLoader` use that ends in `getResources`/`loadClass`: direct calls, method references, JDK internals, generated code, explicit bundle class loaders. The only one of the three that handles all probes.
- **Launcher based mediator, con:** needs the extended bnd launcher until bnd has `LauncherExtension` upstream. One adapter per framework for the bundle class loader path. `StackWalker` on the TCCL path (about 1 µs); a thread with a foreign TCCL bypasses that path. On Equinox the TCCL path pays for the `ContextFinder`, the slowest measured mediated path.
- **Rule of thumb:** weaver by default; launcher based mediator when bytecode must stay untouched or `ServiceLoader` is used from places the weaver cannot reach. They share the registry implementation, not an instance.

## Comparison with Apache Aries SPI Fly

SPI Fly is the reference implementation of the OSGi Service Loader Mediator specification. Its ASM visitor rewrites `ServiceLoader.load(Class)` to `Util.serviceLoaderLoad(Class, Class)` with the woven class as a constant, exactly what the weaver here does with the Class-File API; for other factory methods it wraps the call in a TCCL switch with a generated helper. It never inspects the stack. Providers and consumers come from metadata (`SPI-Provider`/`SPI-Consumer` headers, `osgi.serviceloader` capabilities and requirements, `osgi.extender` for ordering) or, since 1.3, from the framework properties `org.apache.aries.spifly.auto.consumers` and `auto.providers` (symbolic name globs, matched as substrings), which the benchmark uses. There is no notion of class spaces in any mode. For `load(Class)` SPI Fly switches the TCCL to the provider loaders for the duration of the call and invokes the plain `ServiceLoader.load`; for `load(Class, ClassLoader)` it wraps the given loader in a `WrapperCL` created per call.

| | SPI Fly | Weaver here | Launcher based mediator here |
|---|---|---|---|
| Caller | woven class constant | woven class constant | bundle loader hooks, `StackWalker` on the TCCL path |
| Provider discovery | `SPI-Provider` / `osgi.serviceloader` capability / `auto.providers` | `META-INF/services` and `module-info` of every resolved bundle | same |
| Consumer filter | `SPI-Consumer` / `osgi.serviceloader` requirement / `auto.consumers` | class space via package capability | same |
| Start ordering | `osgi.extender` requirement, or framework extension | framework extension | runs before the framework |
| Byte code engine | ASM (framework extension 1.3.7 embeds one that stops at Java 22 class files; the dynamic bundle takes an external ASM, 9.8 reads Java 25, 9.10 Java 27) | `java.lang.classfile` | none |
| Result of `load(Class)` | plain `ServiceLoader` under a temporarily switched TCCL | `ServiceLoader` with a bound `SpiClassLoader` | plain `ServiceLoader`, TCCL is the `SpiClassLoader` |

## Where each one works

Observed with the probes in `org.example.serviceloader.bench` (`PROBE|` lines) unless marked "by design". SPI Fly 1.3.7 as dynamic bundle with ASM 9.10.1 and `auto.consumers=org.example.serviceloader.bench`, `auto.providers=org.example.serviceloader.provider*`.

How to read the cells: the probe bundle is wired to Greeter 1.0; installed are the 1.0 provider bundle with `EnglishGreeter` and `GermanGreeter` and, for the class space row, the 2.0 provider bundle with `FrenchGreeter`. **2 of 2** means exactly the two 1.0 providers came back, **0 of 2** means the mediator did not take part in the lookup (plain JDK behaviour), an error text means the lookup broke.

| Situation | Weaver | Launcher based mediator | SPI Fly |
|---|---|---|---|
| Consumer bundle without any metadata | yes | yes | only if listed in `auto.consumers`, else `SPI-Consumer` or `osgi.serviceloader`/`osgi.extender` requirements |
| Provider bundle with `META-INF/services` only | yes | yes | only if listed in `auto.providers`, else `SPI-Provider` or `osgi.serviceloader` capability |
| Provider declared only in `module-info` (Tyrus) | yes | yes | no (by design) |
| `load(Class)` direct, in a lambda, in a nested class; `load(Class, ClassLoader)` | 2 of 2 | 2 of 2 | 2 of 2 |
| Method reference `ServiceLoader::load` | 2 of 2 (bootstrap argument redirected) | 2 of 2 | **0 of 2** (rewrites call instructions only) |
| `ServiceLoader` inside a library bundle (jakarta.xml.bind, websocket-client) | yes | yes | if the library carries metadata or is listed in `auto.consumers` |
| Bundle provider for a `ServiceLoader` call inside the JDK (`XMLInputFactory.newInstance()`, Woodstox as bundle) | JDK default; **Woodstox with `spi.weaver.tccl=true`** | Woodstox | JDK default; only an `SPI-Consumer: <class>#<method>()` header would help |
| Two versions of the API package installed, consumer wired to 1.0 | 2 of 2 | 2 of 2 | **`ServiceConfigurationError: FrenchGreeter not a subtype`** on every lookup |
| Provider bundle stopped (RESOLVED) | 2 of 2, like packages | 2 of 2 | 0 of 2, tracks STARTING and ACTIVE only; 2 of 2 again after start |
| Providers also registered as OSGi services | no | no | yes (by design) |
| Consumer bytecode | changed at load time | untouched | changed at load time, or at build time with the static tool |
| Launcher / framework | any / any with framework extensions | extended bnd launcher / Felix and Equinox | any / any with weaving hooks |
| Java class file version of consumers | whatever the running JDK reads | irrelevant | bound to the ASM version |

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

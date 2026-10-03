#!/usr/bin/env python3
"""Independent Spring registration and SOFA API-shape expectations."""
import unittest
import java_model_tests as model_tests
from java_annotation_alignment import BASE, ALIAS

API = 'org.springframework.web.bind.annotation'
MVC = {f'{API.replace(".","/")}/{name}.java': f'''package {API}; public @interface {name} {{
    String[] value() default {{}}; String[] path() default {{}};
    String[] consumes() default {{}}; String[] produces() default {{}};
    String[] params() default {{}}; String[] headers() default {{}};
}}''' for name in ['GetMapping','PostMapping','PutMapping','DeleteMapping','PatchMapping']}
MVC |= {f'{API.replace(".","/")}/RequestMapping.java':f'''package {API}; public @interface RequestMapping {{
 String[] value() default {{}}; String[] path() default {{}}; RequestMethod[] method() default {{}};
 String[] consumes() default {{}}; String[] produces() default {{}}; String[] params() default {{}}; String[] headers() default {{}};
}}''', f'{API.replace(".","/")}/RequestMethod.java':f'package {API}; public enum RequestMethod {{ GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS,TRACE }}',
 f'{API.replace(".","/")}/RestController.java':f'package {API}; public @interface RestController {{}}'}
MVC |= {f'{API.replace(".","/")}/{name}.java':f'package {API}; public @interface {name} {{ String value() default ""; boolean required() default true; }}'
        for name in ['PathVariable','RequestParam','RequestBody']}
SOFA = 'com.alipay.sofa.runtime.api.annotation'
SOFA_API = {f'{SOFA.replace(".","/")}/{name}.java':f'package {SOFA}; public @interface {name} {{ Class<?> interfaceType() default void.class; String uniqueId() default ""; }}'
            for name in ['SofaService','SofaReference']}
DI = {
    'org/springframework/beans/factory/annotation/Autowired.java': 'package org.springframework.beans.factory.annotation; public @interface Autowired { boolean required() default true; }',
    'org/springframework/beans/factory/annotation/Qualifier.java': 'package org.springframework.beans.factory.annotation; public @interface Qualifier { String value() default ""; }',
    'org/springframework/beans/factory/annotation/Value.java': 'package org.springframework.beans.factory.annotation; public @interface Value { String value(); }',
    'org/springframework/context/annotation/Primary.java': 'package org.springframework.context.annotation; public @interface Primary {}',
    'org/springframework/context/annotation/Bean.java': 'package org.springframework.context.annotation; public @interface Bean { String[] name() default {}; }',
} | {f'{namespace.replace(".","/")}/Inject.java': f'package {namespace}; public @interface Inject {{}}'
     for namespace in ['javax.inject', 'jakarta.inject']}
DI |= {f'{namespace.replace(".","/")}/Resource.java': f'package {namespace}; public @interface Resource {{ String name() default ""; String lookup() default ""; Class<?> type() default Object.class; }}'
       for namespace in ['javax.annotation', 'jakarta.annotation']}


class Frameworks(unittest.TestCase):
    setUp = model_tests.Models.setUp
    context = model_tests.Models.context
    analyze = model_tests.Models.analyze
    merged = model_tests.Models.merged

    def test_injection_fields_parameters_constructors_and_factory_dependencies(self):
        files = BASE | DI | {'Use.java': '''import org.springframework.beans.factory.annotation.*;
          interface Api {} @org.springframework.stereotype.Component @org.springframework.context.annotation.Primary @Qualifier("preferred")
          class Service implements Api {}
          @org.springframework.stereotype.Component class Use {
            @Autowired(required=false) @Qualifier("preferred") Api field;
            @jakarta.inject.Inject Api jakarta; @javax.inject.Inject Api javax;
            Use(@Qualifier("preferred") Api constructor, @Value("fixed") String value) {}
            @Autowired void configure(@Qualifier("preferred") Api parameter, @Value("fixed") String ignored) {}
            @jakarta.annotation.Resource(name="preferred") void setResource(Api resource) {}
            @org.springframework.context.annotation.Bean Api factory(Api factoryDependency) { return field; }
          }'''}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        registrations = [f for f in facts if f['relation']=='java.spring.injection']
        self.assertEqual(len(registrations), 7)
        self.assertEqual(sorted(f['attributes']['role'] for f in registrations),
                         ['constructor','factory_parameter','field','field','field','method','setter'])
        field = next(f['attributes'] for f in registrations if f['attributes']['injection_point']=='field')
        self.assertFalse(field['required'])
        self.assertEqual(field['qualifier'], 'preferred')
        constructor = next(f['attributes'] for f in registrations if f['attributes']['role']=='constructor')
        self.assertTrue(constructor['implicit_single_constructor'])
        service = next(f['attributes'] for f in facts if f['relation']=='java.bean' and f['name']=='service')
        self.assertTrue(service['primary'])
        self.assertEqual(service['qualifier'], 'preferred')
        self.assertTrue(all(not f['attributes']['runtime_confirmed'] and not f['declaration_resolved'] for f in registrations))
        self.merged([], dict(facts=facts, replacements=[]))

    def test_injection_declaring_type_and_multiline_source_positions(self):
        from java_di_graph_reference import SOURCE
        files=BASE|DI|{'Use.java':SOURCE}
        first=self.analyze([self.context('app',files,spring=True)],cache=True)['facts']
        def positions(facts):
            by={f['id']:f for f in facts}
            return sorted((by[f['attributes']['declaring_type']]['signature'],f['attributes']['injection_point'],f['attributes']['source_line'])
                          for f in facts if f['relation']=='java.spring.injection')
        self.assertEqual(positions(first),[('p.Use','another',6),('p.Use','field',4),('p.Use','ordinary',6),
                                         ('p.Use','ordinary',7),('p.Use$Nested','other',8)])
        changed=SOURCE.replace('Api another) {}','Api another) {\n /* 原字节😀 */ int n=1;\n }')
        delta=self.analyze([self.context('app',files|{'Use.java':changed},spring=True)],first,cache=True)
        merged=self.merged(first,delta)
        full=self.analyze([self.context('app',files|{'Use.java':changed},spring=True)])['facts']
        self.assertEqual(positions(merged),positions(full))
        self.assertEqual(positions(full),[('p.Use','another',6),('p.Use','field',4),('p.Use','ordinary',6),
                                        ('p.Use','ordinary',9),('p.Use$Nested','other',10)])

    def test_injection_unknown_shapes_and_multiple_constructor_negative(self):
        files = BASE | DI | {'Use.java': '''import org.springframework.beans.factory.annotation.*;
          interface Api {} @org.springframework.stereotype.Component class Use {
            Use(Api first) {} Use(Api first, int second) {}
            @Autowired java.util.List<Api> list;
            @Autowired java.util.Map<String,Api> map;
            @Autowired java.util.Map<Integer,Api> unsupported;
            @Autowired @Qualifier("${runtime.name}") Api dynamic;
            @javax.annotation.Resource void invalid(Api first, Api second) {}
          }'''}
        context = self.context('app', files, spring=True)
        first = self.analyze([context])['facts']
        registrations = [f for f in first if f['relation']=='java.spring.injection']
        self.assertEqual(len(registrations), 4)
        self.assertTrue(all(f['attributes']['role']=='field' for f in registrations))
        by_name = {f['attributes']['injection_point']: f['attributes'] for f in registrations}
        self.assertEqual(by_name['list']['element_type'], 'Api')
        self.assertEqual(by_name['map']['cardinality'], 'many')
        self.assertTrue(by_name['unsupported']['matching_unsupported'])
        self.assertTrue(by_name['dynamic']['matching_unsupported'])
        self.assertTrue(any(f['kind']=='coverage' and f['owner']['producer']=='spring-injection-v1'
                            and f['status']=='partial' for f in first))
        files['Use.java'] = files['Use.java'].replace('@Autowired java.util.List<Api> list;', 'java.util.List<Api> list;')
        changed = self.context('app', files, spring=True)
        self.assertEqual(self.merged(first, self.analyze([changed], first)), sorted(self.analyze([changed])['facts'], key=lambda f:f['id']))

    def test_mvc_verbs_class_paths_and_deduplication(self):
        methods = ' '.join(f'@{API}.{verb}Mapping("/items/") void m{verb}(){{}}' for verb in ['Get','Post','Put','Delete','Patch'])
        files = MVC | {'Use.java':f'@{API}.RestController @{API}.RequestMapping({{"/a/","a","/b"}}) class Use {{ {methods} }}'}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        routes = [f for f in facts if f['relation']=='java.spring.mvc']
        self.assertEqual({f['name'] for f in routes}, {f'{verb} {prefix}/items' for verb in ['GET','POST','PUT','DELETE','PATCH'] for prefix in ['/a','/b']})
        self.assertEqual(len(routes), 10)
        self.assertEqual(len([f for f in facts if f['relation']=='handles_possible']), 10)
        self.assertTrue(all(not f['attributes']['runtime_confirmed'] for f in routes))
        self.merged([], dict(facts=facts, replacements=[]))

    def test_request_mapping_enum_methods_and_parameter_bindings(self):
        files = MVC | {'Use.java':f'''@{API}.RestController class Use {{
          @{API}.RequestMapping(path="/items/{{id}}",method={{{API}.RequestMethod.GET,{API}.RequestMethod.POST}},produces="application/json",params="active")
          void handle(@{API}.PathVariable("id") String id,@{API}.RequestParam("active") boolean active,@{API}.RequestBody String body){{}}
        }}'''}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        routes = [f for f in facts if f['relation']=='java.spring.mvc']
        self.assertEqual({f['name'] for f in routes}, {'GET /items/{id}','POST /items/{id}'})
        a = routes[0]['attributes']
        self.assertEqual(a['produces'], ['application/json'])
        self.assertEqual(a['params'], ['active'])
        self.assertEqual([p['binding'] for p in a['parameters']], [API+'.PathVariable',API+'.RequestParam',API+'.RequestBody'])

    def test_composed_mapping_and_runtime_placeholder(self):
        files = MVC | {'Use.java':f'''@{API}.GetMapping("/composed") @interface Composed {{}}
          @{API}.RestController class Use {{ @Composed void good(){{}} @{API}.GetMapping("${{runtime.path}}") void dynamic(){{}} }}'''}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        routes = [f for f in facts if f['relation']=='java.spring.mvc']
        self.assertEqual([f['name'] for f in routes], ['GET /composed'])
        self.assertEqual(routes[0]['attributes']['meta_path'], ['Composed',API+'.GetMapping'])
        self.assertTrue(any(f['kind']=='coverage' and f['owner']['producer']=='spring-mvc-v1' and f['status']=='partial' for f in facts))

    def test_mvc_parameter_spelling_is_original_source_and_binding_is_separate(self):
        source = f'''import java.util.List;
          @{API}.RestController class Use {{
            @{API}.GetMapping("/raw") void read(
              @{API}.RequestParam("names") List< /* 中文😀 */ String > names, String... rest) {{}}
          }}'''
        context = self.context('app', MVC | {'Use.java':source}, spring=True)
        first = self.analyze([context],cache=True)['facts']
        route = next(f for f in first if f['relation']=='java.spring.mvc')
        parameters = route['attributes']['parameters']
        self.assertEqual(parameters[0]['type'], 'List< /* 中文😀 */ String >')
        self.assertEqual(parameters[0]['resolved_type'], 'java.util.List<java.lang.String>')
        self.assertEqual(parameters[0]['attributes']['value'], 'names')
        self.assertEqual(parameters[1]['type'], 'String...')
        self.assertEqual(parameters[1]['resolved_type'], 'java.lang.String[]')
        changed = self.context('app', MVC | {'Use.java':source.replace('rest) {}','rest) {int body=1;}')},spring=True)
        delta = self.analyze([changed],first,cache=True)
        self.assertEqual(self.merged(first,delta),sorted(self.analyze([changed])['facts'],key=lambda f:f['id']))

    def test_lifecycle_and_transactional_event_metadata(self):
        files = {f'{namespace.replace(".","/")}/{name}.java':f'package {namespace}; public @interface {name} {{}}'
                 for namespace in ['javax.annotation','jakarta.annotation'] for name in ['PostConstruct','PreDestroy']}
        files |= {'org/springframework/context/event/EventListener.java':'package org.springframework.context.event; public @interface EventListener { Class<?>[] classes() default {}; String condition() default ""; }',
                  'org/springframework/transaction/event/TransactionPhase.java':'package org.springframework.transaction.event; public enum TransactionPhase { BEFORE_COMMIT,AFTER_COMMIT }',
                  'org/springframework/transaction/event/TransactionalEventListener.java':'package org.springframework.transaction.event; public @interface TransactionalEventListener { TransactionPhase phase() default TransactionPhase.AFTER_COMMIT; boolean fallbackExecution() default false; }',
                  'Use.java':'''class Use {
                    @javax.annotation.PostConstruct void init(){} @jakarta.annotation.PreDestroy void close(){}
                    @org.springframework.context.event.EventListener(classes=String.class,condition="event.valid") void event(String event){}
                    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT) void transactional(String event){}
                  }'''}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        handlers = [f for f in facts if f['relation']=='java.spring.handler']
        self.assertEqual(sorted(f['attributes']['role'] for f in handlers), ['destroy','event','event','init'])
        transaction = next(f['attributes'] for f in handlers if 'transaction_phase' in f['attributes'])
        self.assertEqual(transaction['transaction_phase']['name'], 'BEFORE_COMMIT')
        self.assertEqual(transaction['delivery_status'], 'runtime-dependent')
        self.assertFalse(transaction['runtime_confirmed'])
        self.assertFalse(any(f['kind']=='call_target' and f['origin']=='spring-handlers-v1' for f in facts))

    def test_factory_lifecycle_nearest_method_and_missing_member(self):
        files = {'org/springframework/context/annotation/Bean.java':'package org.springframework.context.annotation; public @interface Bean { String initMethod() default ""; String destroyMethod() default "(inferred)"; }',
                 'Use.java':'''class Base { public void init(){} } class Product extends Base { public void init(){} }
                  class Use { @org.springframework.context.annotation.Bean(initMethod="init",destroyMethod="missing") Product product(){return new Product();} }'''}
        facts = self.analyze([self.context('app', files, spring=True)])['facts']
        handlers = [f for f in facts if f['relation']=='java.spring.handler']
        self.assertEqual({f['attributes']['configured_method'] for f in handlers}, {'init','missing'})
        init = next(f for f in handlers if f['attributes']['role']=='init')
        product = next(f for f in facts if f['signature']=='Product#init()')
        self.assertTrue(any(f['relation']=='handles_possible' and f['parent']==init['id'] and f['target']==product['id'] for f in facts))
        self.assertTrue(any(f['kind']=='coverage' and f['owner']['producer']=='spring-handlers-v1' for f in facts))

    def test_sofa_annotation_roles_and_inferred_contract(self):
        files = SOFA_API | {'Use.java':f'''interface Api {{}} @{SOFA}.SofaService(uniqueId="provider") class Service implements Api {{}}
          class Use {{ @{SOFA}.SofaReference Api api; @{SOFA}.SofaReference(interfaceType=Api.class,uniqueId="explicit") Object other; }}'''}
        facts = self.analyze([self.context('app', files, sofa=True)])['facts']
        registrations = [f for f in facts if f['relation']=='java.sofa' and f['owner']['producer']=='sofa-annotations-v1']
        self.assertEqual(len(registrations), 3)
        self.assertEqual({f['attributes']['role'] for f in registrations}, {'provider','requirement'})
        self.assertTrue(all(f['attributes']['contract']=='Api' and f['attributes']['contract_supported'] for f in registrations))
        self.assertFalse(any(f['attributes']['runtime_confirmed'] for f in registrations))

    def test_sofa_unknown_api_dynamic_unique_and_multiple_contracts(self):
        for option in ['unknown-api','dynamic','ambiguous']:
            with self.subTest(option=option):
                files = dict(SOFA_API)
                if option=='unknown-api':
                    path = SOFA.replace('.','/')+'/SofaService.java'
                    files[path] = files[path].replace('String uniqueId()', 'int version() default 1; String uniqueId()')
                unique = '${runtime.id}' if option=='dynamic' else 'fixed'
                interfaces = 'Api,Other' if option=='ambiguous' else 'Api'
                files['Use.java'] = f'interface Api {{}} interface Other {{}} @{SOFA}.SofaService(uniqueId="{unique}") class Use implements {interfaces} {{}}'
                facts = self.analyze([self.context('app', files, sofa=True)])['facts']
                registration = next(f for f in facts if f['relation']=='java.sofa')
                self.assertFalse(registration['attributes']['contract_supported'])
                self.assertEqual(registration['attributes']['contract_baseline'], 'unverified-api')
                self.assertTrue(any(f['kind']=='coverage' and f['owner']['producer']=='sofa-annotations-v1' for f in facts))

    def test_framework_disable_delta_removes_all_registration_producers(self):
        files = MVC | SOFA_API | {'Use.java':f'@{API}.RestController @{SOFA}.SofaService(interfaceType=Runnable.class) class Use {{ @{API}.GetMapping("/item") void run(){{}} }}'}
        first = self.analyze([self.context('app', files, spring=True, sofa=True)])['facts']
        self.assertTrue(any(f['relation']=='java.spring.mvc' for f in first))
        disabled = self.context('app', files, spring=False, sofa=False)
        delta = self.analyze([disabled], first)
        full = self.analyze([disabled])
        self.assertEqual(self.merged(first, delta), sorted(full['facts'], key=lambda f:f['id']))
        self.assertFalse(any(f['owner']['producer'].startswith(('spring-','sofa-')) for f in full['facts']))

    def test_reduced_body_growth_refreshes_registration_spans(self):
        files = MVC | {'Other.java':f'@{API}.RestController class Other {{ @{API}.GetMapping("/other") String route(){{return "other";}} }}',
                       'Use.java':f'@{API}.RestController class Use {{ void grow(){{System.out.println("short"+new Other().route());}} @{API}.GetMapping("/item") void run(){{}} }}'}
        first = self.analyze([self.context('app', files, spring=True)], cache=True)['facts']
        old = next(f['span'] for f in first if f['relation']=='java.spring.mvc' and f['name']=='GET /item')
        files['Use.java'] = files['Use.java'].replace('short','a much longer method body literal')
        context = self.context('app', files, spring=True)
        delta = self.analyze([context], first, cache=True)
        full = self.analyze([context])
        self.assertEqual(delta['reduced_contexts'], 1)
        self.assertFalse(any(o['kind']=='source' and o['id']=='app/Other.java' for o in delta['replacements']))
        self.assertNotEqual(old, next(f['span'] for f in full['facts'] if f['relation']=='java.spring.mvc' and f['name']=='GET /item'))
        self.assertEqual(self.merged(first, delta), sorted(full['facts'], key=lambda f:f['id']))

    def test_reduced_fact_view_preserves_unchanged_bean_and_lifecycle_dependencies(self):
        bean = 'package org.springframework.context.annotation; public @interface Bean { String initMethod() default ""; }'
        inject = 'package org.springframework.beans.factory.annotation; public @interface Autowired {}'
        files = BASE | {'org/springframework/context/annotation/Bean.java':bean,
                        'org/springframework/beans/factory/annotation/Autowired.java':inject,
                        'Product.java':'class Product { public void init(){} }',
                        'Service.java':'@org.springframework.stereotype.Component class Service {}',
                        'Use.java':'''class Use { void grow(){System.out.println("short");}
                          @org.springframework.context.annotation.Bean(initMethod="init") Product product(){return new Product();}
                          @org.springframework.beans.factory.annotation.Autowired Service service;
                        }'''}
        first = self.analyze([self.context('app', files, spring=True)], cache=True)['facts']
        files['Use.java'] = files['Use.java'].replace('short','a much longer method body literal')
        context = self.context('app', files, spring=True)
        delta = self.analyze([context], first, cache=True)
        full = self.analyze([context])
        self.assertEqual(delta['reduced_contexts'], 1)
        self.assertTrue(any(f['relation']=='injects_possible' for f in self.merged(first, delta)))
        self.assertTrue(any(f['relation']=='handles_possible' for f in delta['facts']))
        self.assertEqual(self.merged(first, delta), sorted(full['facts'], key=lambda f:f['id']))


if __name__=='__main__':
    unittest.main(verbosity=2)

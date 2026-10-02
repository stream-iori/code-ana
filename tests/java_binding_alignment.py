#!/usr/bin/env python3
"""Compiler binding failures and model recovery must preserve separate evidence."""
import unittest
import shutil
import subprocess
import zipfile
import java_model_tests as models
import java_lombok_alignment as lombok_tests


class Bindings(unittest.TestCase):
    setUp = models.Models.setUp
    context = models.Models.context
    analyze = models.Models.analyze
    merged = models.Models.merged

    def overrides(self, facts):
        by_id = {f['id']: f for f in facts}
        self.merged([], dict(facts=facts, replacements=[]))
        return [(by_id[f['parent']]['signature'], by_id[f['target']]['signature'], f)
                for f in facts if f['relation'] == 'overrides']

    def test_modeled_overrides_substitute_source_generics_and_reject_illegal_methods(self):
        files = lombok_tests.Lombok.files(self, '''
          @lombok.Getter class Parent<T> { T value; int count; }
          class Child extends Parent<String> {
            @Override public String getValue(){return "value";}
            @Override public int getCount(){return 1;}
          }
          class BadReturn extends Parent<String> { public int getValue(){return 1;} }
          class ReducedVisibility extends Parent<String> { String getValue(){return "value";} }
          class StaticChild extends Parent<String> { public static String getValue(){return "value";} }
          @lombok.Getter(lombok.AccessLevel.PRIVATE) class PrivateParent { String value; }
          class PrivateChild extends PrivateParent { public String getValue(){return "value";} }
        ''')
        context = self.context('app', files, lombok='model')
        facts = self.analyze([context])['facts']
        edges = self.overrides(facts)
        self.assertEqual({(a, b) for a, b, _ in edges}, {
            ('Child#getValue()', 'Parent#getValue()'),
            ('Child#getCount()', 'Parent#getCount()')})
        for _, _, fact in edges:
            self.assertEqual((fact['origin'], fact['status']), ('modeled_override', 'possible'))
            self.assertFalse(fact['declaration_resolved'])
            self.assertFalse(fact['evidence_complete'])
            self.assertTrue(fact['attributes']['modeled_input'])
        gaps = [f for f in facts if f['kind']=='diagnostic'
                and f['attributes'].get('code')=='MODELED_OVERRIDE_INCOMPLETE']
        self.assertEqual(len(gaps), 2, gaps)
        self.assertTrue(all(f['attributes']['message']=='illegal-modeled-override' for f in gaps))
        changed = dict(files)
        changed['App.java'] = changed['App.java'].replace('extends Parent<String>', 'extends Parent<Integer>').replace(
            'public String getValue(){return "value";}', 'public Integer getValue(){return 1;}')
        updated = self.context('app', changed, lombok='model')
        delta = self.analyze([updated], facts)
        self.assertEqual(self.merged(facts, delta), sorted(self.analyze([updated])['facts'], key=lambda f:f['id']))

    def test_generated_overrides_cover_interfaces_covariance_and_preserve_javac(self):
        files = lombok_tests.Lombok.files(self, '''
          interface Contract<T> { T getValue(); void setValue(T value); int getCount(); }
          @lombok.Getter @lombok.Setter class Bean implements Contract<String> {
            String value; int count;
          }
          interface Wide { CharSequence getValue(); }
          @lombok.Getter class Narrow implements Wide { String value; }
          class Explicit implements Wide { public String getValue(){return "yes";} }
          @lombok.Data class Data { int value; }
        ''')
        facts = self.analyze([self.context('app', files, lombok='model')])['facts']
        edges = self.overrides(facts)
        modeled = {(a,b) for a,b,f in edges if f['origin']=='modeled_override'}
        self.assertEqual(modeled, {
            ('Bean#getValue()', 'Contract#getValue()'),
            ('Bean#setValue(java.lang.String)', 'Contract#setValue(java.lang.Object)'),
            ('Bean#getCount()', 'Contract#getCount()'),
            ('Narrow#getValue()', 'Wide#getValue()'),
            ('Data#equals(java.lang.Object)', 'java.lang.Object#equals(java.lang.Object)'),
            ('Data#hashCode()', 'java.lang.Object#hashCode()'),
            ('Data#toString()', 'java.lang.Object#toString()')})
        explicit = next(f for a,b,f in edges if a=='Explicit#getValue()' and b=='Wide#getValue()')
        self.assertEqual(explicit['origin'], 'javac')
        self.assertEqual(explicit['status'], 'complete')
        self.assertTrue(explicit['evidence_complete'])
        self.assertFalse([f for f in facts if f['kind']=='diagnostic'
                          and f['attributes'].get('code')=='MODELED_OVERRIDE_INCOMPLETE'])

    def test_modeled_overrides_binary_generics_raw_erasure_and_final_guards(self):
        source = self.root/'binary-src/ext/Contract.java'
        source.parent.mkdir(parents=True)
        source.write_text('package ext; public interface Contract<T> { T getValue(); void setValue(T value); }')
        final = source.with_name('FinalBase.java')
        final.write_text('package ext; public class FinalBase { public final String getValue(){return "final";} }')
        classes = self.root/'binary-classes'
        classes.mkdir()
        subprocess.run([str(models.JDK8/'bin/javac'), '-proc:none', '-d', str(classes), str(source), str(final)],
                       check=True, capture_output=True, timeout=30)
        jar = self.root/'contracts.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            for path in sorted(classes.rglob('*.class')):
                archive.write(path, path.relative_to(classes).as_posix())
        witness = self.root/'RawWitness.java'
        witness.write_text('''class RawWitness implements ext.Contract {
          Object value; @Override public Object getValue(){return value;}
          @Override public void setValue(Object value){this.value=value;}
        }''')
        subprocess.run([str(models.JDK8/'bin/javac'), '-proc:none', '-classpath', str(jar),
                        '-d', str(classes), str(witness)], check=True, capture_output=True, timeout=30)
        shutil.rmtree(classes)
        files = lombok_tests.Lombok.files(self, '''
          @lombok.Getter @lombok.Setter class Bean implements ext.Contract<String> { String value; }
          @lombok.Getter @lombok.Setter class Raw implements ext.Contract { Object value; }
          @lombok.Getter class Illegal extends ext.FinalBase { String value; }
        ''')
        facts = self.analyze([self.context('app', files, lombok='model', classpath=[str(jar)])])['facts']
        edges = self.overrides(facts)
        self.assertEqual({(a,b) for a,b,_ in edges}, {
            ('Bean#getValue()', 'ext.Contract#getValue()'),
            ('Bean#setValue(java.lang.String)', 'ext.Contract#setValue(java.lang.Object)'),
            ('Raw#getValue()', 'ext.Contract#getValue()'),
            ('Raw#setValue(java.lang.Object)', 'ext.Contract#setValue(java.lang.Object)')})
        by_id = {f['id']:f for f in facts}
        for _,_,edge in edges:
            target = by_id[edge['target']]
            self.assertEqual(target['kind'], 'external_symbol')
            self.assertFalse(target['evidence_complete'])
            # A javac-owned declaration may already exist; modeled-only metadata stays possible.
            if target['origin']=='binary_metadata':
                self.assertEqual(target['status'], 'possible')
                self.assertEqual(target['attributes']['extension_producer'], 'modeled-override-v1')
        self.assertTrue(any(f['kind']=='diagnostic' and f['attributes'].get('message')=='illegal-modeled-override'
                            for f in facts))

    def test_modeled_overrides_package_paths_and_raw_parameter_subsignatures(self):
        files = lombok_tests.Lombok.files(self, '''
          interface Lists { void setValues(java.util.List<String> values); }
          @lombok.Setter class RawList implements Lists { java.util.List values; }
          @lombok.Setter class BadList implements Lists { java.util.List<Integer> values; }
          @lombok.Getter class Parent<T extends Number> { T value; }
          class RawChild extends Parent { public Number getValue(){return null;} }
        ''')
        files.update({
            'p/Parent.java': 'package p; @lombok.Getter(lombok.AccessLevel.PACKAGE) public class Parent { String value; }',
            'p/Local.java': 'package p; public class Local extends Parent { String getValue(){return "yes";} }',
            'q/Bridge.java': 'package q; public class Bridge extends p.Parent {}',
            'p/Return.java': 'package p; class Return extends q.Bridge { public String getValue(){return "no";} }',
        })
        facts = self.analyze([self.context('app', files, lombok='model')])['facts']
        self.assertEqual({(a,b) for a,b,_ in self.overrides(facts)}, {
            ('RawList#setValues(java.util.List)', 'Lists#setValues(java.util.List)'),
            ('RawChild#getValue()', 'Parent#getValue()'),
            ('p.Local#getValue()', 'p.Parent#getValue()')})
        self.assertTrue(any(f['kind']=='diagnostic' and f['attributes'].get('message')=='illegal-modeled-override'
                            for f in facts))

    def test_failure_codes_selected_declaration_and_constructor_argument_types(self):
        context = self.context('app', {'Use.java': '''class Api {
          private void hidden(){} void takes(String input){}
          void ambiguous(String input){} void ambiguous(Integer input){}
          void instance(){} static void visible(int value){}
        } class Use { void run(Api api){ api.hidden(); api.takes(1);
          api.ambiguous(null); api.missing(); Api.instance(); api.visible(1); new Api(1); }
        '''})
        facts = self.analyze([context])['facts']
        calls = {f['name']: f for f in facts if f['kind']=='call_site'}
        expected = {'api.hidden':'inaccessible', 'api.takes':'inapplicable',
                    'api.ambiguous':'ambiguous', 'api.missing':'missing_symbol',
                    'Api.instance':'static_context', 'Api':'inapplicable'}
        for name, reason in expected.items():
            call = calls[name]
            self.assertEqual(call['attributes']['binding_failure'], reason, call)
            self.assertTrue(call['attributes']['diagnostic_codes'])
            self.assertEqual(call['status'], 'unresolved')
            self.assertFalse([f for f in facts if f['kind']=='call_target' and f['parent']==call['id']])
        call = calls['api.visible']
        self.assertEqual(call['attributes']['binding_failure'], 'none')
        self.assertEqual(call['attributes']['javac_selected_declaration'], 'Api#visible(int)')
        self.assertEqual(call['attributes']['argument_type_models'][0]['kind'], 'INT')
        self.assertEqual(call['attributes']['caller_type'], 'Use')
        self.assertTrue(call['evidence_complete'])
        constructor = calls['Api']['attributes']
        self.assertEqual(constructor['argument_count'], 1)
        self.assertEqual(constructor['lookup_name'], '<init>')
        self.assertEqual(constructor['argument_type_models'][0]['kind'], 'INT')
        self.assertEqual(constructor['receiver_type_model']['element'], 'Api')
        self.merged([],dict(facts=facts,replacements=[]))

    def test_type_variables_retain_class_and_shadowing_method_owner(self):
        context = self.context('app', {'Use.java': '''class Api<T> {
          T read(T value){return value;}
          <T extends Number> T echo(T value){return value;}
          <U extends T> U relay(U value){return value;}
        } class Use { void run(Api<String> api){ api.read("s"); api.echo(1); api.relay("x"); } }
        '''})
        facts = self.analyze([context])['facts']
        declarations = {f['signature']: f for f in facts if f['kind']=='declaration'}
        self.assertEqual(declarations['Api']['attributes']['type_parameter_models'][0]['owner'], 'Api')
        read = declarations['Api#read(java.lang.Object)']['attributes']['parameter_type_models'][0]
        echo = declarations['Api#echo(java.lang.Number)']['attributes']['parameter_type_models'][0]
        self.assertEqual((read['name'],read['owner']), ('T','Api'))
        self.assertEqual((echo['name'],echo['owner']), ('T','Api#echo(java.lang.Number)'))
        relay = declarations['Api#relay(java.lang.Object)']['attributes']['parameter_type_models'][0]
        self.assertEqual(relay['owner'], 'Api#relay(java.lang.Object)')
        self.assertEqual(relay['upper']['owner'], 'Api')
        selected = next(f for f in facts if f['kind']=='call_site' and f['name']=='api.echo')
        self.assertEqual(selected['attributes']['selected_type_model']['return']['element'], 'java.lang.Integer')

    def test_private_static_and_generic_modeled_getter_guards(self):
        files = lombok_tests.Lombok.files(self, '''class Bean<T> {
          @lombok.Getter(lombok.AccessLevel.PRIVATE) T secret;
          @lombok.Getter int number; @lombok.Getter static int count;
          T own(){return getSecret();} static int invalid(){return getNumber();}
          static class Nested { Object nest(Bean<String> b){return b.getSecret();} }
        } class Use { void run(Bean<String> bean){ bean.getSecret(); Bean.getNumber();
          bean.getNumber(); Bean.getCount(); new PrivateBean(); } }
        @lombok.NoArgsConstructor(access=lombok.AccessLevel.PRIVATE) class PrivateBean {}
        ''')
        context = self.context('app', files, lombok='model')
        first = self.analyze([context])['facts']
        declarations = {f['id']: f for f in first if f['kind']=='declaration'}
        sites = {f['id']: f for f in first if f['kind']=='call_site'}
        recovered = [(sites[f['parent']]['name'], declarations[f['target']]['signature'])
                     for f in first if f['kind']=='call_target' and f['origin']=='lombok_model']
        self.assertEqual(recovered.count(('bean.getSecret','Bean#getSecret()')),0)
        self.assertEqual(recovered.count(('Bean.getNumber','Bean#getNumber()')),0)
        self.assertEqual(recovered.count(('getNumber','Bean#getNumber()')),0)
        self.assertIn(('getSecret','Bean#getSecret()'),recovered)
        self.assertIn(('b.getSecret','Bean#getSecret()'),recovered)
        self.assertIn(('bean.getNumber','Bean#getNumber()'),recovered)
        self.assertIn(('Bean.getCount','Bean#getCount()'),recovered)
        self.assertFalse([pair for pair in recovered if pair[1]=='PrivateBean#<init>()'])
        changed = dict(files)
        changed['App.java'] = changed['App.java'].replace('return getSecret();','return null;')
        context = self.context('app',changed,lombok='model')
        delta = self.analyze([context],first)
        self.assertEqual(self.merged(first,delta),sorted(self.analyze([context])['facts'],key=lambda f:f['id']))

    def recovered(self, facts):
        declarations = {f['id']:f for f in facts if f['kind'] in ('declaration','external_symbol')}
        sites = {f['id']:f for f in facts if f['kind']=='call_site'}
        targets = [f for f in facts if f['kind']=='call_target' and f['status']=='possible']
        self.assertTrue(all(not f['evidence_complete'] for f in targets))
        # A modeled override can supersede javac's selected Object/JDK member;
        # the call site still preserves the original compiler evidence.
        self.assertTrue(all(sites[f['parent']]['status'] in ('resolved','unresolved') for f in targets))
        self.merged([],dict(facts=facts,replacements=[]))
        return [(sites[f['parent']]['name'],declarations[f['target']]['signature'],f) for f in targets]

    def test_generic_inherited_setter_capture_and_primitive_conversions(self):
        files = lombok_tests.Lombok.files(self, '''class Base<T> {
          @lombok.Getter @lombok.Setter T value; @lombok.Setter long number;
        } class Child extends Base<String> {}
        class Use { void run(Child child, Base<? extends String> read,
          Base<? super String> write, Base<String> concrete) {
          child.setValue("ok"); child.setValue(1); child.setNumber(1);
          read.setValue("bad"); read.setValue(null); write.setValue("ok");
          concrete.setValue("yes"); concrete.setValue(new Object());
        } }''')
        context = self.context('app', files, lombok='model')
        facts = self.analyze([context])['facts']
        recovered = self.recovered(facts)
        pairs = [(name,signature) for name,signature,_ in recovered]
        self.assertEqual(pairs.count(('child.setValue','Base#setValue(java.lang.Object)')),1)
        self.assertIn(('child.setNumber','Base#setNumber(long)'),pairs)
        self.assertEqual(pairs.count(('read.setValue','Base#setValue(java.lang.Object)')),1) # null only
        self.assertIn(('write.setValue','Base#setValue(java.lang.Object)'),pairs)
        self.assertEqual(pairs.count(('concrete.setValue','Base#setValue(java.lang.Object)')),1)
        target = next(f for name,_,f in recovered if name=='child.setValue')
        self.assertEqual(target['attributes']['recovered_parameter_type_models'][0]['element'],'java.lang.String')
        self.assertFalse(target['declaration_resolved'])

    def test_chained_source_overloads_phases_varargs_and_generic_methods(self):
        files = lombok_tests.Lombok.files(self, '''class Holder { @lombok.Getter Api api; }
          class Api {
            void choose(long value){} void choose(Integer value){}
            void spread(String first, String... rest){}
            void clash(String value){} void clash(Integer value){}
            <T extends Number> T echo(T input){return input;}
            void invariant(java.util.List<String> input){}
            void wildcard(java.util.List<? extends Number> input){}
          } class Use { void run(Holder h, java.util.List<Integer> ints){
            h.getApi().choose(1); h.getApi().spread("a","b","c");
            h.getApi().clash(null); h.getApi().echo(1); h.getApi().echo("bad");
            h.getApi().invariant(ints); h.getApi().wildcard(ints);
          } }''')
        context = self.context('app',files,lombok='model')
        facts = self.analyze([context])['facts']; recovered = self.recovered(facts)
        pairs = [(name,signature) for name,signature,_ in recovered]
        self.assertIn(('h.getApi().choose','Api#choose(long)'),pairs)
        self.assertIn(('h.getApi().spread','Api#spread(java.lang.String,java.lang.String[])'),pairs)
        self.assertFalse([pair for pair in pairs if pair[0]=='h.getApi().clash'])
        self.assertEqual(pairs.count(('h.getApi().echo','Api#echo(java.lang.Number)')),1)
        self.assertFalse([pair for pair in pairs if pair[0]=='h.getApi().invariant'])
        self.assertIn(('h.getApi().wildcard','Api#wildcard(java.util.List)'),pairs)
        echo = next(f for name,_,f in recovered if name=='h.getApi().echo')
        self.assertEqual(echo['origin'],'source_recovery')
        self.assertTrue(echo['declaration_resolved'])
        self.assertEqual(echo['attributes']['recovery_phase'],1)
        self.assertEqual(echo['attributes']['recovered_return_type_model']['element'],'java.lang.Integer')
        spread = next(f for name,_,f in recovered if name=='h.getApi().spread')
        self.assertEqual(spread['attributes']['recovery_phase'],2)
        changed = dict(files); changed['App.java'] = changed['App.java'].replace('void choose(long value)','void choose(double value)')
        updated = self.context('app',changed,lombok='model')
        delta = self.analyze([updated],facts)
        old_owners = {tuple(sorted(f['owner'].items())) for f in facts if f['source']=='app/App.java'}
        replacements = {tuple(sorted(owner.items())) for owner in delta['replacements']}
        self.assertTrue(old_owners <= replacements, old_owners - replacements)
        self.assertEqual(self.merged(facts,delta), sorted(self.analyze([updated])['facts'],key=lambda f:f['id']))

    def test_modeled_constructor_requires_applicable_arguments(self):
        files = lombok_tests.Lombok.files(self, '''@lombok.AllArgsConstructor class Bean<T> {
          final T value; final long number;
        } class Use { void run(){ new Bean<String>("yes",1); new Bean<String>(1,1);
          new Bean<String>("yes"); } }''')
        facts = self.analyze([self.context('app',files,lombok='model')])['facts']
        recovered = self.recovered(facts)
        self.assertEqual([signature for _,signature,_ in recovered], ['Bean#<init>(java.lang.Object,long)'])
        self.assertFalse(recovered[0][2]['declaration_resolved'])

    def test_explicit_type_arguments_bounds_arity_null_arrays_and_unattributed_reference(self):
        files = lombok_tests.Lombok.files(self, '''class Holder { @lombok.Getter Api api; }
          class Api {
            public <T extends Number> T echo(T value){return value;}
            public <T> T identity(T value){return value;}
            public <T extends Number> T empty(){return null;}
            public void plain(int value){}
          } class Use { void run(Holder h) {
            h.getApi().<Number>echo(1); h.getApi().<String>echo(1);
            h.getApi().<Integer>echo("bad"); h.getApi().<Integer,String>echo(1);
            h.getApi().<Integer>empty(); h.getApi().<String>identity(null);
            h.getApi().<int[]>identity(null);
            h.getApi().<String>plain(1); h.getApi().<Missing>identity(null);
            java.util.function.Function<Integer,Number> ref=h.getApi()::<Number>echo;
          } }''')
        context = self.context('app',files,lombok='model')
        facts = self.analyze([context])['facts']; recovered = self.recovered(facts)
        source = [(name,signature,target) for name,signature,target in recovered if target['origin']=='source_recovery']
        self.assertEqual(len(source),5,[(name,signature) for name,signature,_ in source])
        by_id = {f['id']:f for f in facts}
        for _,signature,target in source:
            site = by_id[target['parent']]
            explicit = site['attributes']['explicit_type_argument_models'][0]
            if signature=='Api#echo(java.lang.Number)':
                self.assertEqual(explicit['element'],'java.lang.Number')
                self.assertEqual(target['attributes']['recovered_return_type_model']['element'],'java.lang.Number')
            elif signature=='Api#empty()':
                self.assertEqual(explicit['element'],'java.lang.Integer')
                self.assertEqual(target['attributes']['recovered_return_type_model']['element'],'java.lang.Integer')
            elif signature=='Api#identity(java.lang.Object)':
                self.assertTrue(explicit.get('element')=='java.lang.String'
                                or explicit.get('kind')=='ARRAY' and explicit['component']['kind']=='INT')
        reference = next(f for f in facts if f['kind']=='call_site' and f['attributes']['mechanism']=='method_reference')
        self.assertEqual(reference['attributes']['explicit_type_argument_models'][0]['kind'],'UNKNOWN')
        self.assertEqual(reference['attributes']['recovery_failure'],'explicit_type_arguments_unavailable')
        self.assertFalse([f for f in facts if f['kind']=='call_target' and f['parent']==reference['id']])
        changed = dict(files); changed['App.java'] = changed['App.java'].replace('<Integer>empty()','<Number>empty()')
        updated = self.context('app',changed,lombok='model')
        self.assertEqual(self.merged(facts,self.analyze([updated],facts)),sorted(self.analyze([updated])['facts'],key=lambda f:f['id']))

    def test_binary_generic_hierarchy_method_shadowing_and_varargs(self):
        library = self.root/'library/lib'; library.mkdir(parents=True)
        (library/'Base.java').write_text('''package lib; public class Base<T extends Number> {
          public T accept(T value){return value;}
          public <T extends CharSequence> T echo(T value){return value;}
          public void spread(String... values){} private void hidden(){}
        }''')
        (library/'Child.java').write_text('package lib; public class Child extends Base<Integer> {}')
        (library/'Outer.java').write_text('''package lib; public class Outer<T> {
          public class Inner<U> { public T outer(T value){return value;}
            public U inner(U value){return value;} }
        }''')
        classes = self.root/'compiled'; classes.mkdir()
        subprocess.run([str(models.JDK8/'bin/javac'),'-proc:none','-d',str(classes),
                        str(library/'Base.java'),str(library/'Child.java'),str(library/'Outer.java')],check=True,capture_output=True,timeout=30)
        jar = self.root/'api.jar'
        subprocess.run([str(models.JDK8/'bin/jar'),'cf',str(jar),'-C',str(classes),'.'],check=True,capture_output=True,timeout=30)
        shutil.rmtree(classes)
        files = lombok_tests.Lombok.files(self, '''class Holder { @lombok.Getter lib.Child api;
          @lombok.Getter lib.Outer<String>.Inner<Integer> nested; }
          class Use { void run(Holder h){ h.getApi().accept(1); h.getApi().accept("bad");
            h.getApi().echo("yes"); h.getApi().echo(1); h.getApi().<CharSequence>echo("yes");
            h.getApi().<Integer>echo(1); h.getApi().spread("a","b"); h.getApi().hidden();
            h.getNested().outer("yes"); h.getNested().outer(1); h.getNested().inner(1); h.getNested().inner("bad"); } }''')
        facts = self.analyze([self.context('app',files,lombok='model',classpath=[str(jar)])])['facts']
        recovered = self.recovered(facts); pairs = [(n,s) for n,s,_ in recovered]
        self.assertEqual(pairs.count(('h.getApi().accept','lib.Base#accept(java.lang.Number)')),1)
        self.assertEqual(pairs.count(('h.getApi().echo','lib.Base#echo(java.lang.CharSequence)')),2)
        self.assertIn(('h.getApi().spread','lib.Base#spread(java.lang.String[])'),pairs)
        self.assertFalse([pair for pair in pairs if pair[0]=='h.getApi().hidden'])
        self.assertEqual(pairs.count(('h.getNested().outer','lib.Outer$Inner#outer(java.lang.Object)')),1)
        self.assertEqual(pairs.count(('h.getNested().inner','lib.Outer$Inner#inner(java.lang.Object)')),1)
        echo = next(f for name,_,f in recovered if name=='h.getApi().echo'
                    and f['attributes']['recovered_return_type_model']['element']=='java.lang.String')
        self.assertEqual(echo['origin'],'binary_recovery')
        self.assertEqual(echo['attributes']['recovered_return_type_model']['element'],'java.lang.String')
        external = next(f for f in facts if f['signature']=='lib.Base#echo(java.lang.CharSequence)')
        formal = external['attributes']['binary_member']['type_model']['type_parameters'][0]
        self.assertEqual(formal['owner'],'lib.Base#echo(java.lang.CharSequence)')
        explicit = next(f for name,_,f in recovered if name=='h.getApi().echo'
                        and f['attributes']['recovered_return_type_model']['element']=='java.lang.CharSequence')
        self.assertEqual(explicit['origin'],'binary_recovery')

    def test_missing_argument_and_incomplete_hierarchy_do_not_select_other_overload(self):
        files = lombok_tests.Lombok.files(self, '''class Holder { @lombok.Getter Api api; }
          class Api extends MissingParent { void take(Object value){} void take(Missing value){} }
          class Use { void run(Holder h){ h.getApi().take("s"); h.getApi().take(missing()); } }''')
        facts = self.analyze([self.context('app',files,lombok='model')])['facts']
        recovered = self.recovered(facts)
        self.assertFalse([pair for pair in recovered if pair[0]=='h.getApi().take'])

    def test_bound_unbound_static_constructor_and_wildcard_method_references(self):
        files = lombok_tests.Lombok.files(self, '''class Bean<T> {
          @lombok.Getter @lombok.Setter T value; @lombok.Getter static int number;
          @lombok.Getter(lombok.AccessLevel.PRIVATE) int hidden;
        } @lombok.NoArgsConstructor class Created {}
        class Use { void run(Bean<String> b){
          java.util.function.Supplier<String> read=b::getValue;
          java.util.function.Consumer<String> write=b::setValue;
          java.util.function.Consumer<? super String> ground=b::setValue;
          java.util.function.Function<Bean<String>,String> unbound=Bean<String>::getValue;
          java.util.function.IntSupplier statik=Bean::getNumber;
          java.util.function.Supplier<Created> create=Created::new;
          java.util.function.Supplier<Integer> badReturn=b::getValue;
          java.util.function.Consumer<Integer> badArgument=b::setValue;
          java.util.function.IntSupplier privateMember=b::getHidden;
          java.util.function.IntSupplier boundStatic=b::getNumber;
        } }''')
        facts = self.analyze([self.context('app',files,lombok='model')])['facts']
        recovered = self.recovered(facts); pairs = [(n,s) for n,s,_ in recovered]
        self.assertEqual(pairs.count(('b::getValue','Bean#getValue()')),1)
        self.assertEqual(pairs.count(('b::setValue','Bean#setValue(java.lang.Object)')),2)
        self.assertIn(('Bean<String>::getValue','Bean#getValue()'),pairs)
        self.assertIn(('Bean::getNumber','Bean#getNumber()'),pairs)
        self.assertIn(('Created::new','Created#<init>()'),pairs)
        self.assertFalse([p for p in pairs if p[0] in ('b::getHidden','b::getNumber')])
        self.assertEqual(len(pairs),6)
        self.assertTrue(all(f['attributes'].get('recovered_sam') for _,_,f in recovered))

    def test_zero_varargs_specificity_and_nonfunctional_targets(self):
        files = lombok_tests.Lombok.files(self, '''class Holder { @lombok.Getter Api api; }
          class Api { void spread(Object... xs){} void spread(String... xs){} }
          interface Broken { String first(); String second(); }
          abstract class Almost { abstract String first(); }
          class Bean { @lombok.Getter String value; }
          class Use { void run(Holder h, Bean b){ h.getApi().spread();
            Broken broken=b::getValue; Almost invalid=b::getValue; } }''')
        facts = self.analyze([self.context('app',files,lombok='model')])['facts']
        recovered = self.recovered(facts); pairs = [(n,s) for n,s,_ in recovered]
        self.assertIn(('h.getApi().spread','Api#spread(java.lang.String[])'),pairs)
        self.assertFalse([p for p in pairs if p[0]=='b::getValue'])

    def test_recursive_intersection_bounds_keep_formal_identity(self):
        files = lombok_tests.Lombok.files(self, '''class Holder { @lombok.Getter Api api; }
          class Api { <T extends Number & Comparable<T>> T echo(T value){return value;} }
          class Use { void run(Holder h){ h.getApi().echo(1); h.getApi().echo("bad"); } }''')
        facts = self.analyze([self.context('app',files,lombok='model')])['facts']
        recovered = self.recovered(facts)
        self.assertEqual([(n,s) for n,s,_ in recovered if n=='h.getApi().echo'], [('h.getApi().echo','Api#echo(java.lang.Number)')])
        declaration = next(f for f in facts if f['signature']=='Api#echo(java.lang.Number)')
        recursive = declaration['attributes']['type_parameter_models'][0]['bounds'][1]['arguments'][0]
        self.assertEqual(recursive['owner'],'Api#echo(java.lang.Number)')
        self.assertEqual(recursive['name'],'T')


if __name__ == '__main__':
    unittest.main(verbosity=2)

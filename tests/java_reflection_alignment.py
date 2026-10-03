#!/usr/bin/env python3
"""Exact static reflection lookup and adversarial flow under real Java 8 javac."""
import unittest
import java_model_tests as model_tests


class Reflection(unittest.TestCase):
    setUp = model_tests.Models.setUp
    context = model_tests.Models.context
    analyze = model_tests.Models.analyze
    merged = model_tests.Models.merged

    def fixture(self, body, **options):
        text = '''import java.lang.reflect.*;
          class Base { public void inherited(String value) {} }
          class Target extends Base {
            public Target() {} private Target(int value) {}
            public void run() {} public void other() {} private void hidden(int value) {}
            public void array(String[] values) {} public void overloaded(String value) {} public void overloaded(Integer value) {}
          }
          class Use { static final String NAME="run"; static void escape(Class<?>[] types) {}
            void inspect(boolean flag, String dynamic) throws Exception { BODY }
          }'''.replace('BODY', body)
        return self.context('reflect', {'Use.java':text}, **options)

    def evidence(self, facts, operation):
        return [f for f in facts if f['relation']=='java.reflection' and f['attributes']['operation']==operation]

    def targets(self, facts):
        by_id = {f['id']: f for f in facts}
        return [(f, by_id[f['target']]) for f in facts if f['kind']=='call_target' and f['origin']=='reflection_model']

    def test_reflection_lookup_retains_lexical_control_and_full_delta(self):
        body = """try {
            if(flag) { Target.class.getMethod("run"); }
            Target.class.getDeclaredConstructor();
          } catch(Exception error) { Target.class.getMethod("other"); }
          Target.class.getMethod("run");"""
        context = self.fixture(body)
        facts = self.analyze([context])['facts']
        edges = [f for f in facts if f['relation']=='reflection_lookup']
        self.assertEqual(len(edges), 4)
        controls = [f['attributes'].get('type_context') for f in sorted(edges,key=lambda f:f['span']['start'])]
        self.assertRegex(controls[0], r'^try@L[0-9]+>if-then@L[0-9]+$')
        self.assertRegex(controls[1], r'^try@L[0-9]+$')
        self.assertRegex(controls[2], r'^catch@L[0-9]+$')
        self.assertIsNone(controls[3])
        changed = self.fixture(body.replace('if(flag) { Target', 'if(!flag) { Target'))
        delta = self.analyze([changed], facts)
        full = self.analyze([changed])['facts']
        self.assertEqual(self.merged(facts, delta), sorted(full,key=lambda f:f['id']))

    def test_constant_names_class_values_exact_methods_and_constructors(self):
        ctx = self.fixture('''Class<?> type=Class.forName("Tar"+"get");
          Method method=type.getMethod(NAME); method.invoke(new Target());
          Method hidden=Target.class.getDeclaredMethod("hidden", Integer.TYPE); hidden.invoke(new Target(),1);
          Constructor<?> constructor=Target.class.getDeclaredConstructor(int.class); constructor.newInstance(1);
          Method inherited=Target.class.getMethod("inherited",String.class); inherited.invoke(new Target(),"value");
          Target.class.getMethod("array",String[].class).invoke(new Target(),(Object)new String[0]);''')
        facts = self.analyze([ctx])['facts']
        selected = {target['signature'] for _, target in self.targets(facts)}
        self.assertEqual(selected, {'Target#run()', 'Target#hidden(int)', 'Target#<init>(int)',
                                    'Base#inherited(java.lang.String)', 'Target#array(java.lang.String[])'})
        self.assertEqual(len(self.evidence(facts, 'METHOD_INVOKE')), 4)
        self.assertEqual(len(self.evidence(facts, 'CONSTRUCTOR_NEW_INSTANCE')), 1)
        for edge, _ in self.targets(facts):
            self.assertEqual(edge['status'], 'possible')
            self.assertFalse(edge['declaration_resolved'])
            self.assertFalse(edge['evidence_complete'])
            self.assertFalse(edge['attributes']['runtime_confirmed'])
        self.merged([], dict(facts=facts, replacements=[]))

    def test_switch_merges_exhaustive_states_and_preserves_fallthrough_uncertainty(self):
        body = '''String exhaustive="other"; switch(flag ? 0 : 1) {
          case 0: exhaustive="run"; break; default: exhaustive="run";
        } Target.class.getMethod(exhaustive).invoke(new Target());
        String absent="other"; switch(flag ? 0 : 1) { case 0: absent="run"; break; }
        Target.class.getMethod(absent).invoke(new Target());
        String conflict="other"; switch(flag ? 0 : 1) {
          case 0: conflict="run"; break; default: conflict="other";
        } Target.class.getMethod(conflict).invoke(new Target());
        String carried="other"; switch(flag ? 0 : 1) {
          case 0: carried="run"; case 1: Target.class.getMethod(carried).invoke(new Target()); break;
          default: break;
        }
        String overwritten="other"; switch(flag ? 0 : 1) {
          case 0: overwritten="run"; case 1: overwritten="run"; break;
          default: overwritten="run";
        } Target.class.getMethod(overwritten).invoke(new Target());'''
        context=self.fixture(body)
        original=self.analyze([context])['facts']
        self.assertFalse([f for f in original if f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR'])
        self.assertEqual([target['signature'] for _,target in self.targets(original)],['Target#run()','Target#run()'])
        changed=self.fixture(body.replace('exhaustive="run"','exhaustive="other"'))
        delta=self.analyze([changed],original)
        full=self.analyze([changed])['facts']
        self.assertEqual(self.merged(original,delta),sorted(full,key=lambda f:f['id']))
        self.assertEqual({target['signature'] for _,target in self.targets(full)},{'Target#run()','Target#other()'})

    def test_equal_branch_values_merge_across_constant_and_handle_provenance(self):
        body='''String name="other"; if(flag) name=NAME; else name="run";
          Target.class.getMethod(name).invoke(new Target());
          Class<?> type; if(flag) type=Target.class; else type=Class.forName("Target");
          type.getMethod("run").invoke(new Target());
          Target.class.getMethod(flag ? NAME : "run").invoke(new Target());
          Target.class.getMethod(flag ? NAME : "other").invoke(new Target());'''
        context=self.fixture(body)
        original=self.analyze([context])['facts']
        self.assertFalse([f for f in original if f['kind']=='diagnostic' and f['attributes'].get('severity')=='ERROR'])
        self.assertEqual([target['signature'] for _,target in self.targets(original)],['Target#run()']*3)
        self.assertTrue(all(not edge['declaration_resolved'] and not edge['evidence_complete']
                            for edge,_ in self.targets(original)))
        changed=self.fixture(body.replace('else name="run"','else name="other"'))
        delta=self.analyze([changed],original)
        full=self.analyze([changed])['facts']
        self.assertEqual(self.merged(original,delta),sorted(full,key=lambda f:f['id']))
        self.assertEqual(len(self.targets(full)),2)

    def test_public_declared_wrong_parameter_dynamic_and_api_spoofing(self):
        ctx = self.fixture('''Target.class.getMethod("hidden",int.class).invoke(new Target(),1);
          Target.class.getDeclaredMethod("inherited",String.class).invoke(new Target(),"x");
          Target.class.getMethod("run",String.class).invoke(new Target(),"x");
          Target.class.getMethod(dynamic).invoke(new Target());
          Class<?> type=Class.forName(dynamic); type.getMethod("run").invoke(new Target());''')
        facts = self.analyze([ctx])['facts']
        self.assertEqual(self.targets(facts), [])
        spoof = self.context('spoof', {'Fake.java':'''class Fake {
          Fake forName(String name){return this;} Fake getMethod(String name){return this;}
          Object invoke(Object target){return null;} void run(){forName("Fake").getMethod("run").invoke(this);}
        }'''})
        self.assertFalse(any(f['relation']=='java.reflection' for f in self.analyze([spoof])['facts']))

    def test_binary_interface_has_no_object_methods_or_inherited_static_methods(self):
        ctx = self.fixture('''Runnable.class.getMethod("toString").invoke(null);
          java.util.List.class.getMethod("getClass").invoke(null);
          Target.class.getDeclaredMethod("run").invoke(new Target());''')
        facts = self.analyze([ctx])['facts']
        self.assertEqual([target['signature'] for _,target in self.targets(facts)], ['Target#run()'])

    def test_parameter_branch_agreement_and_declared_lookup_with_missing_parent(self):
        ctx = self.fixture('''if(flag){dynamic="run";}else{dynamic="run";}
          Target.class.getDeclaredMethod(dynamic).invoke(new Target());''')
        facts = self.analyze([ctx])['facts']
        self.assertEqual([target['signature'] for _,target in self.targets(facts)], ['Target#run()'])
        broken = self.context('broken', {'Use.java':'''class Target extends Missing { public void run(){} }
          class Use { void inspect() throws Exception {Target.class.getDeclaredMethod("run").invoke(null);} }'''})
        facts = self.analyze([broken])['facts']
        self.assertTrue(any(f['kind']=='diagnostic' and f['status']=='ERROR' for f in facts))
        self.assertEqual([target['signature'] for _,target in self.targets(facts)], ['Target#run()'])

    def test_branch_agreement_conflict_shadowing_and_reassignment(self):
        ctx = self.fixture('''String name; if(flag){name="run";}else{name="run";}
          Target.class.getMethod(name).invoke(new Target());
          if(flag){name="run";}else{name="other";}
          Target.class.getMethod(name).invoke(new Target());
          name="run"; Method method=Target.class.getMethod(name);
          { String other="other"; Target.class.getMethod(other).invoke(new Target()); }
          method.invoke(new Target()); name=dynamic; Target.class.getMethod(name).invoke(new Target());
          method=null; method.invoke(new Target());''')
        facts = self.analyze([ctx])['facts']
        self.assertEqual(sorted(target['signature'] for _,target in self.targets(facts)),
                         ['Target#other()', 'Target#run()', 'Target#run()'])

    def test_loops_try_finally_and_short_circuit_invalidate_uncertain_values(self):
        ctx = self.fixture('''String name="run";
          while(flag){Target.class.getMethod(name).invoke(new Target()); name=dynamic;}
          Target.class.getMethod(name).invoke(new Target());
          name="run"; boolean ignored=flag && (name=dynamic).isEmpty();
          Target.class.getMethod(name).invoke(new Target());
          name="run"; try { name=dynamic; } catch(RuntimeException error) { name="other"; }
          finally { name="run"; } Target.class.getMethod(name).invoke(new Target());''')
        facts = self.analyze([ctx])['facts']
        self.assertEqual([target['signature'] for _,target in self.targets(facts)], ['Target#run()'])

    def test_class_array_alias_mutation_and_escape(self):
        ctx = self.fixture('''Class<?>[] types={String.class}; Class<?>[] alias=types;
          Target.class.getMethod("inherited",types).invoke(new Target(),"x");
          alias[0]=Integer.class; Target.class.getMethod("inherited",types).invoke(new Target(),"x");
          types=new Class<?>[]{String.class}; escape(types);
          Target.class.getMethod("inherited",types).invoke(new Target(),"x");
          Target.class.getMethod("run",new Class<?>[]{}).invoke(new Target());''')
        facts = self.analyze([ctx])['facts']
        self.assertEqual(sorted(target['signature'] for _,target in self.targets(facts)),
                         ['Base#inherited(java.lang.String)', 'Target#run()'])

    def test_binary_lookup_and_full_delta_equivalence(self):
        first = self.fixture('String.class.getMethod("substring",int.class).invoke("value",1);')
        old = self.analyze([first], cache=True)['facts']
        reflected = self.targets(old)
        self.assertEqual([target['signature'] for _,target in reflected], ['java.lang.String#substring(int)'])
        self.assertEqual(reflected[0][1]['kind'], 'external_symbol')
        changed = self.fixture('String.class.getMethod("substring",int.class,int.class).invoke("value",1,2);')
        delta = self.analyze([changed], old, cache=True)
        full = self.analyze([changed])
        self.assertEqual(self.merged(old, delta), sorted(full['facts'], key=lambda f:f['id']))
        deleted = self.fixture('String.class.getMethod(dynamic);')
        self.assertEqual(self.merged(full['facts'], self.analyze([deleted], full['facts'])),
                         sorted(self.analyze([deleted])['facts'], key=lambda f:f['id']))

    def test_nested_class_parameters_use_binary_identity(self):
        context = self.context('nested', {'Use.java': '''class Outer { static class Inner {} }
          class Target { public void nested(Outer.Inner value, Outer.Inner[][] values){} }
          class Use { void run() throws Exception {
            Target.class.getMethod("nested",Outer.Inner.class,Outer.Inner[][].class).invoke(null,null,null);
          } }'''})
        facts = self.analyze([context])['facts']
        self.assertEqual([target['signature'] for _,target in self.targets(facts)],
                         ['Target#nested(Outer$Inner,Outer$Inner[][])'])

    def test_constructor_and_closure_escapes_invalidate_array_proofs(self):
        ctx = self.fixture('''Class<?>[] types={String.class};
          new Object() { void mutate(){types[0]=Integer.class;} };
          Target.class.getMethod("inherited",types).invoke(new Target(),"x");
          final Class<?>[] captured={String.class}; Runnable mutate=()->captured[0]=Integer.class; mutate.run();
          Target.class.getMethod("inherited",captured).invoke(new Target(),"x");
          final Class<?>[] escaped={String.class}; new Holder(escaped);
          Target.class.getMethod("inherited",escaped).invoke(new Target(),"x");''')
        text = ctx['sources'][0]
        import pathlib
        path = pathlib.Path(text['input'])
        path.write_text(path.read_text()+' class Holder { Holder(Class<?>[] types){types[0]=Integer.class;} }')
        text['hash'] = model_tests.sha(path.read_bytes())
        self.assertEqual(self.targets(self.analyze([ctx])['facts']), [])

    def test_depth_budget_is_disclosed_as_partial(self):
        ctx = self.fixture('String name='+'('*280+'"run"'+')'*280+'; Target.class.getMethod(name).invoke(new Target());')
        facts = self.analyze([ctx])['facts']
        self.assertEqual(self.targets(facts), [])
        self.assertTrue(any(f['kind']=='diagnostic' and f['name']=='REFLECTION_FLOW_BUDGET' for f in facts))
        self.assertTrue(any(f['kind']=='coverage' and f['owner']['producer']=='javac-java8-v1'
                            and f['status']=='partial' for f in facts))


if __name__=='__main__':
    unittest.main(verbosity=2)

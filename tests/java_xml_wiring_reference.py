"""Compare published Rust XML wiring with an actual frozen Anatomist index."""
import json
import pathlib
import sqlite3

import java_functional_reference_alignment as reference
from java_xml_reference_alignment import index_reference

SOURCE = '''package p;
class Api {}
class Holder {Holder(){} Holder(Api api){} void setOther(Object other){} void setItems(Object items){}}
class Product {void setOther(Api other){}}
class Maker {static Product create(){return new Product();}}
'''
XML = '''<beans xmlns="http://www.springframework.org/schema/beans">
 <bean id="api" class="p.Api"/>
 <bean id="unknown" class="missing.External"/>
 <bean id="holder" class="p.Holder">
  <constructor-arg ref="api"/>
  <property name="other" ref="api"/>
  <property name="items"><list>
   <ref bean="unknown"/>
   <idref bean="api"/>
   <bean id="inner" class="p.Holder"><property name="other" ref="api"/></bean>
  </list></property>
 </bean>
 <bean id="made" class="p.Maker" factory-method="create">
  <property name="other" ref="api"/>
 </bean>
 <bean id="parentOnly" parent="holder"><property name="other" ref="api"/></bean>
 <bean id="externalOwner" class="missing.Owner"><property name="other" ref="api"/></bean>
</beans>'''


def projection(facts, directory, source=SOURCE, xml=XML):
    directory = pathlib.Path(directory)
    directory.mkdir()
    db = index_reference(directory, source, xml)
    before = reference.digest(db)
    by = {f['id']: f for f in facts}
    product = []
    for f in facts:
        if f['relation'] != 'wires_possible' or f['attributes'].get('via') != 'xml_configuration':
            continue
        site = by[f['attributes']['configuration_evidence']]
        if site['kind'] != 'reference' or site['relation'] != 'config':
            raise ValueError('XML_WIRING_CONFIGURATION_EVIDENCE_MISSING')
        if not (f['source'] == site['source'] and f['span'] == site['span']):
            raise ValueError('XML_WIRING_POSITION_DRIFT')
        if f['status'] != 'possible' or f['evidence_complete'] or f['declaration_resolved']:
            raise ValueError('XML_WIRING_RUNTIME_SELECTION_FABRICATED')
        target = by[f['target']]
        product.append(dict(source=by[f['parent']]['signature'], target=target['signature'],
                            external=target['kind'] == 'external_symbol',
                            line=f['attributes']['position']['line']))
    with sqlite3.connect(db.resolve().as_uri()+'?mode=ro', uri=True) as conn:
        conn.row_factory = sqlite3.Row
        nodes = {row['id']: dict(row) for row in conn.execute('SELECT * FROM nodes')}
        expected = [dict(source=nodes[e['source_id']]['symbol_id'],
                         target=nodes[e['target_id']]['symbol_id'] if e['target_id'] else e['external_target_fqn'],
                         external=bool(e['is_external']), line=e['begin_line'])
                    for e in conn.execute("SELECT * FROM edges WHERE relation='WIRES'")]
    order = lambda row: json.dumps(row, sort_keys=True)
    product, expected = sorted(product, key=order), sorted(expected, key=order)
    receipt = dict(reference_sha=reference.REFERENCE_SHA, reference_db_sha256=before,
                   database_drift=before != reference.digest(db),
                   captured_xml_sha256=reference.digest(directory/'app/src/main/resources/beans.xml'),
                   product=product, reference=expected, accepted=product == expected,
                   full_semantic_diff_accepted=False)
    (directory/'wiring-diff.json').write_text(json.dumps(receipt, indent=2)+'\n')
    if receipt['database_drift']:
        raise ValueError('XML_WIRING_REFERENCE_DATABASE_DRIFT')
    return product, expected

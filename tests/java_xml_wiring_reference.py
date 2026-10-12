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


def product_projection(facts, include_file=False):
    by = {f['id']: f for f in facts}
    product = []
    for f in facts:
        if f['relation'] != 'wires_possible' or f['attributes'].get('via') != 'xml_configuration':
            continue
        attributes=f['attributes']
        if attributes.get('upstream_relation') == 'WIRES':
            # The copied XML extractor publishes its own exact graph edge. Its
            # line-only position must not be replaced by a guessed reference span.
            if attributes.get('upstream_producer') != 'spring-xml':
                raise ValueError('XML_WIRING_PROVIDER_DRIFT')
            if attributes.get('position') != attributes.get('upstream_position'):
                raise ValueError('XML_WIRING_POSITION_DRIFT')
            if not any(row['kind']=='coverage' and row['source']==f['source'] for row in facts):
                raise ValueError('XML_WIRING_CAPTURE_MISSING')
        else:
            site = by[attributes['configuration_evidence']]
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
        if include_file:
            product[-1]['file'] = f['source']
    return product


def reference_projection(conn):
    return [dict(source=e['source_symbol'], target=e['target_symbol'] or e['external_target_fqn'],
                 external=bool(e['is_external']), line=e['begin_line'], file=e['source_file'])
            for e in conn.execute("SELECT e.*,s.symbol_id source_symbol,t.symbol_id target_symbol "
                "FROM edges e JOIN nodes s ON s.id=e.source_id LEFT JOIN nodes t ON t.id=e.target_id "
                "WHERE e.relation='WIRES' AND e.producer_id='spring-xml'")]


def projection(facts, directory, source=SOURCE, xml=XML):
    directory = pathlib.Path(directory)
    directory.mkdir()
    db = index_reference(directory, source, xml)
    before = reference.digest(db)
    product = product_projection(facts)
    # This fixture has one XML resource. Full-project audit retains its path.
    with sqlite3.connect(db.resolve().as_uri()+'?mode=ro', uri=True) as conn:
        conn.row_factory = sqlite3.Row
        expected = [{key:value for key,value in row.items() if key!='file'}
                    for row in reference_projection(conn)]
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

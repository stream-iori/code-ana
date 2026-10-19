"""Check frozen call expectations against published typed graph facts."""
import json
import sqlite3


def audit_calls(database, cases):
    results = []
    with sqlite3.connect(database) as db:
        for case in cases:
            line, column = case['line']+1, case['character']+1
            if case.get('operation', 'calls') == 'references':
                rows = db.execute('''SELECT e.id,e.begin_line,e.begin_column,e.end_line,e.end_column,
                           n.source_file,n.begin_line,e.external_target_fqn,e.metadata
                    FROM edges e LEFT JOIN nodes n ON n.id=e.target_id
                    WHERE e.relation='REFERENCES' AND e.source_file=? AND e.begin_line<=? AND e.end_line>=?''',
                    (case['file'], line, line)).fetchall()
            else:
                rows = db.execute('''SELECT hex(c.stable_hash),c.begin_line,c.begin_column,c.end_line,c.end_column,
                       n.source_file,n.begin_line,t.external_target_fqn,c.metadata
                FROM call_sites c JOIN call_site_owners o USING(owner_pk)
                LEFT JOIN call_site_targets t ON t.call_site_pk=c.site_pk
                LEFT JOIN nodes n ON n.id=t.target_id
                WHERE o.source_file=? AND c.begin_line<=? AND c.end_line>=?''',
                    (case['file'], line, line)).fetchall()
            rows = [r for r in rows if (r[1] < line or r[2] <= column) and (r[3] > line or r[4] >= column)]
            if rows:
                nearest = min(rows, key=lambda r: (r[3]-r[1], r[4]-r[2]))[0]
                rows = [r for r in rows if r[0] == nearest]
            actual = sorted({(r[5], r[6]-1) for r in rows if r[5] is not None})
            expected = sorted({(e['file'], e['line']) for e in case['expected']})
            reasons = sorted({reason for r in rows for reason in json.loads(r[8] or '{}').get('python_unknown_reasons', [])})
            results.append(dict(id=case['id'], passed=bool(rows) and actual == expected and (bool(expected) or bool(reasons)),
                                actual=actual, expected=expected, unknown_reasons=reasons,
                                external_targets=sorted({r[7] for r in rows if r[7]}),
                                operation=case.get('operation', 'calls'),
                                call_site='call-site:'+rows[0][0].lower() if rows and case.get('operation', 'calls') == 'calls' else None,
                                fact_id=(rows[0][0] if case.get('operation') == 'references' else 'call-site:'+rows[0][0].lower()) if rows else None))
    return results

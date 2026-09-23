#!/usr/bin/env python3
"""Validate DATA-01 fixtures using Python 3 standard library only."""
import hashlib
import json
import re
import sys
import zipfile
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads((ROOT / path).read_text())


def decimal(value, label):
    require(isinstance(value, str) and re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", value), f"{label}: non-negative decimal string required")
    return Decimal(value)


def local_file(path):
    resolved = (ROOT / path).resolve()
    require(resolved.is_relative_to(ROOT / 'data'), f"Invalid local reference: {path}")
    require(resolved.is_file(), f"Missing fixture: {path}")
    return resolved


def validate_products(products):
    ids, articles = set(), set()
    required = read('data/sample_catalog/catalog.schema.json')['$defs']['product']['required']
    for p in products:
        require(set(p) == set(required), "Missing or unexpected product field")
        require(p['synthetic'] is True, "Unmarked synthetic product")
        require(isinstance(p['id'], str) and re.fullmatch(r'[1-9][0-9]*', p['id']), "Product ID must be a decimal string")
        require(isinstance(p['article'], str) and p['article'].strip(), "SKU must be text")
        normalized = p['article'].strip().upper()
        require(all(isinstance(p[k],str) and p[k].strip() for k in ['name','brand','sourceVersion']), 'Invalid product label')
        require(p['id'] not in ids, 'Duplicate ID')
        require(normalized not in articles, 'Duplicate article')
        ids.add(p['id']); articles.add(normalized)
        require(p['category'] in {'breakers','cables','lamps'}, 'Unknown category')
        require(p['unit'] == ('m' if p['category'] == 'cables' else 'pcs'), 'Category/unit mismatch')
        minimum, step = decimal(p['minimum'],'minimum'), decimal(p['step'],'step')
        require(minimum > 0 and step > 0 and minimum % step == 0, 'Invalid minimum/step')
        price = decimal(p['price'],'price')
        require(price > 0 and price.as_tuple().exponent >= -2, 'Invalid price precision')
        require(p['currency'] == 'KZT', 'Unexpected currency')
        require(isinstance(p['specs'], dict) and all(isinstance(v,str) for v in p['specs'].values()), 'Invalid specs')
        require(p['sourceVersion'] == 'synthetic-v1', 'Invalid source version')
        require(p['sourceUrl'].startswith('https://example.invalid/'), 'Only synthetic source URLs allowed')
        require(p['certificates'], 'Missing certificate')
        for c in p['certificates']:
            require(c['synthetic'] is True and c['version'] == '1', 'Invalid certificate metadata')
            require(local_file(c['file']).read_bytes().startswith(b'%PDF-'), 'Invalid certificate file')
        warehouses=set()
        for w in p['warehouses']:
            require(w['warehouseId'] not in warehouses, 'Duplicate warehouse')
            warehouses.add(w['warehouseId'])
            require(isinstance(w['eligible'],bool), 'Warehouse eligibility required')
            status, qty = w['status'], w['availableQuantity']
            require(status in {'IN_STOCK','OUT_OF_STOCK','ON_ORDER','UNKNOWN'}, 'Unknown stock status')
            if status in {'UNKNOWN','ON_ORDER'}:
                require(qty is None, 'Unknown/on-order quantity cannot be invented')
            else:
                q = decimal(qty,'availableQuantity')
                require(q % step == 0, 'Stock violates step')
                require((q > 0) == (status == 'IN_STOCK'), 'Stock status/quantity conflict')
        require(warehouses, 'No warehouse evidence')


def quantity(p):
    amounts = [decimal(w['availableQuantity'],'stock') for w in p['warehouses'] if w['eligible'] and w['status'] in {'IN_STOCK','OUT_OF_STOCK'}]
    return sum(amounts,Decimal(0))


def compatible(a,b,rules):
    if a['category'] != b['category'] or a['unit'] != b['unit']:
        return False
    return all(k in a['specs'] and k in b['specs'] and a['specs'][k] == b['specs'][k] for k in rules[a['category']])


def validate():
    catalog=read('data/sample_catalog/products.json')
    require(catalog['synthetic'] is True and catalog['schemaVersion'] == 1, 'Invalid manifest')
    products=catalog['products']
    require(len(products)>=30 and len({p['category'] for p in products})>=3, 'Insufficient catalog coverage')
    validate_products(products)
    by_id={p['id']:p for p in products}
    require(by_id['1001']['article']=='000001', 'Leading zeros lost')
    require({p['article'] for p in products} >= {'Aa','BB'}, 'Missing Java hash-collision fixtures')
    require(by_id['1006']['warehouses'][0]['availableQuantity'] is None, 'UNKNOWN must remain null')
    for fixture in ['duplicate-article','negative-quantity']:
        invalid=read(f'data/sample_catalog/invalid/{fixture}.json')
        try:
            validate_products(invalid['products'])
        except ValueError:
            pass
        else:
            raise ValueError(f'Negative fixture was accepted: {fixture}')
    scenarios=read('data/expected/scenarios.json')['scenarios']
    require({s['id'] for s in scenarios} == {f'AC-{i}' for i in range(1,12)}, 'AC coverage mismatch')
    rules=read('data/sample_catalog/compatibility-rules.json')['rules']
    base=by_id['1001']
    require(quantity(base)==12, 'Eligible stock must exclude CLOSED warehouse')
    require(not compatible(base,by_id['1004'],rules), '32A is not a 16A replacement')
    require(not compatible(base,by_id['1007'],rules), 'Unknown hard attribute cannot be verified')
    require(quantity(by_id['1005']) == 0 and compatible(by_id['1005'],base,rules), 'Zero-stock analog fixture broken')
    fulfillment=next(s for s in scenarios if s['id']=='AC-9')
    for key in ['split','whole']:
        require(sum(decimal(l['quantity'],'quantity') for l in fulfillment[key]) == 20, f'{key}: total must equal 20')
        for line in fulfillment[key]:
            p=by_id[line['productId']]; q=decimal(line['quantity'],'quantity')
            require(compatible(base,p,rules), f'{key}: incompatible item')
            require(q <= quantity(p) and q >= decimal(p['minimum'],'minimum') and q % decimal(p['step'],'step') == 0, f'{key}: invalid quantity')
    cart=read('data/expected/cart-snapshots.json')
    for state in ['before','after']:
        c=cart[state]
        require(decimal(c['quantity'],'quantity')*decimal(c['price'],'price')==decimal(c['total'],'total'), 'Cart fixture arithmetic mismatch')
    terms=read('data/purchase_terms/terms.json')['documents']
    sources={d['id']:d for d in terms}
    require(len(sources)==len(terms), 'Duplicate source ID')
    for case in read('data/expected/terms-answers.json')['cases']:
        if case['sourceId']:
            source=sources[case['sourceId']]
            require(source['version']==case['sourceVersion'], 'Citation version mismatch')
            require(all(f in source['text'] for f in case['expectedFacts']), 'FAQ not grounded in fixture')
    snapshots=read('data/expected/offer-snapshots.json')['snapshots']
    require({s['id'] for s in snapshots}=={'stock12-to7','price-change','timeout','on-order'}, 'Missing offer scenario')
    require(next(s for s in snapshots if s['id']=='timeout')['quantity'] is None, 'Timeout becomes zero')
    rows=read('data/expected/attachment-rows.json')['rows']
    require(rows[0]['article']=='000001' and decimal(rows[1]['quantity'],'quantity')==Decimal('2.5'), 'Extraction expectations broken')
    for row in rows:
        if row['productId'] is not None:
            require(by_id[row['productId']]['article']==row['article'], 'Row product reference mismatch')
    files=read('data/attachments/manifest.json')['files']
    require({f['family'] for f in files}>={'xls','xlsx','doc','docx','pdf','jpg'}, 'Missing file family')
    for f in files:
        p=local_file(f['file']); raw=p.read_bytes()
        require(hashlib.sha256(raw).hexdigest()==f['sha256'], f'Checksum mismatch: {p.name}')
        if f['expectedRows']:
            local_file(f['expectedRows'])
            require(f['expectedRowOccurrences'] in {1,2}, 'Missing expected row occurrences')
        if f['expected']=='REJECT': continue
        kind=f['family']
        magic={'xls':b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1','doc':b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1','pdf':b'%PDF-','jpg':b'\xff\xd8\xff'}
        if kind in magic: require(raw.startswith(magic[kind]), f'Invalid {kind} signature')
        if kind in {'xlsx','docx'}:
            with zipfile.ZipFile(p) as z:
                require(z.testzip() is None, 'Corrupt ZIP')
                xml=''.join(z.read(n).decode() for n in z.namelist() if n.endswith('.xml'))
                require('000001' in xml and '000013' in xml, f'Lost identifiers in {kind}')
                require('20' in xml and ('2.5' in xml or '2,5' in xml), f'Lost quantities in {kind}')
                require(not any('vbaProject' in n for n in z.namelist()), 'Unexpected macro')
                if kind=='xlsx':
                    ns={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
                    sheet=ET.fromstring(z.read('xl/worksheets/sheet1.xml'))
                    cells={c.attrib['r']:c for c in sheet.findall('.//s:c',ns)}
                    require(cells['A2'].attrib.get('t') in {'s','inlineStr','str'}, 'SKU stored as number')
                    require(Decimal(cells['C3'].find('s:v',ns).text)==Decimal('2.5'), 'Fractional amount lost')
    questions=read('data/expected/questions.json')['cases']
    require(len(questions)>=50 and len({q['id'] for q in questions})==len(questions), 'Question coverage/IDs invalid')
    require({q['split'] for q in questions}=={'tuning','heldout'}, 'Heldout split missing')
    for q in questions:
        require(all(i in by_id for i in q.get('expectedProductIds',[])), 'Unknown expected product')
        require(all(i in sources for i in q.get('expectedSourceIds',[])), 'Unknown expected source')
    print(f'PASS DATA-01: {len(products)} products / 3 categories / {len(terms)} sources / {len(files)} files / {len(questions)} questions')
    print('PASS: decimal quantities, leading zeros, 12+8/whole20, unknown vs zero, invalid fixtures, references and checksums')


if __name__=='__main__':
    try:
        validate()
    except (ValueError,KeyError,TypeError,OSError,zipfile.BadZipFile) as exc:
        print(f'FAIL DATA-01: {exc}',file=sys.stderr)
        sys.exit(1)

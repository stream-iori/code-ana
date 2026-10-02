#!/usr/bin/env python3
"""Malformed source isolation keeps original bytes and healthy semantic facts."""
import unittest,json
import java_model_tests as models
sha=models.sha
class Encodings(unittest.TestCase):
 setUp=models.Models.setUp;context=models.Models.context;analyze=models.Models.analyze;merged=models.Models.merged
 def case(self,all_invalid=False,byte=None):
  context=self.context('encoding',{'Broken.java':'class Broken {}',**({} if all_invalid else {'Healthy.java':'class Healthy {int ok(){return 1;}}'})})
  data=byte if byte is not None else 'class Broken {} // 单元测试'.encode('gbk')
  source=self.root/'encoding/Broken.java';source.write_bytes(data);context['sources'][0]['hash']=sha(data)
  context['digest']=sha(json.dumps([(s['path'],s['hash']) for s in context['sources']]).encode());return context,data
 def verify(self,facts,data):
  errors=[f for f in facts if f['kind']=='diagnostic' and f['name']=='SOURCE_ENCODING_INVALID'];self.assertEqual(len(errors),1)
  self.assertEqual(errors[0]['source'],'encoding/Broken.java');self.assertEqual(errors[0]['attributes']['sha256'],sha(data));self.assertIsNone(errors[0]['span'])
  coverage={f['source']:f['status'] for f in facts if f['kind']=='coverage'};self.assertEqual(coverage['encoding/Broken.java'],'partial')
  self.assertEqual((self.root/'encoding/Broken.java').read_bytes(),data)
  self.assertFalse([f for f in facts if f['kind']=='declaration' and f['source']=='encoding/Broken.java'])
  return coverage
 def test_healthy_isolation(self):
  c,b=self.case();facts=self.analyze([c])['facts'];self.assertEqual(self.verify(facts,b)['encoding/Healthy.java'],'complete')
  self.assertTrue([f for f in facts if f['signature']=='Healthy#ok()'])
 def test_all_invalid_is_partial_not_crash(self):
  c,b=self.case(all_invalid=True);facts=self.analyze([c])['facts'];self.assertEqual(self.verify(facts,b),{'encoding/Broken.java':'partial'})
 def test_bad_to_good_and_deletion_delta(self):
  c,b=self.case();old=self.analyze([c],cache=True)['facts'];self.verify(old,b)
  c=self.context('encoding',{'Broken.java':'class Broken {}','Healthy.java':'class Healthy {int ok(){return 1;}}'})
  delta=self.analyze([c],old,cache=True);full=self.analyze([c],cache=True)['facts'];self.assertEqual(self.merged(old,delta),sorted(full,key=lambda f:f['id']))
  self.assertFalse([f for f in full if f['name']=='SOURCE_ENCODING_INVALID'])
  c=self.context('encoding',{'Healthy.java':'class Healthy {int ok(){return 1;}}'});self.assertEqual(self.merged(full,self.analyze([c],full,cache=True)),sorted(self.analyze([c],cache=True)['facts'],key=lambda f:f['id']))
 def test_bad_byte_changes_replace_original_diagnostic(self):
  c,b=self.case(byte=b'class Broken {} // \xff');old=self.analyze([c],cache=True)['facts'];self.verify(old,b)
  c,b=self.case(byte=b'class Broken {} // \xfe');delta=self.analyze([c],old,cache=True);full=self.analyze([c],cache=True)['facts'];self.verify(full,b)
  self.assertEqual(self.merged(old,delta),sorted(full,key=lambda f:f['id']))

if __name__ == "__main__":
 unittest.main(verbosity=2)

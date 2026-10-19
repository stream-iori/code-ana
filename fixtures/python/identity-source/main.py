# coding: utf-8
值 = '😀'

def 外层(参数: str):
    def 同名():
        return 参数
    return 同名()

def 另一个():
    def 同名():
        return 值
    return 同名()

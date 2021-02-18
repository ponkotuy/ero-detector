#!/usr/bin/env python
# -*- coding:utf-8 -*-

import sys

import os
from os import path

from image_helper import SplitedImage, ColorHuMoment

if __name__ == "__main__":
    args = sys.argv
    files = filter(path.isfile, args[1:])
    images = (SplitedImage(fname) for fname in files)
    huMoments = [ColorHuMoment(img) for img in images]
    huMoments.sort(key=lambda hus: hus.first())
    remove_list = []
    for x, y in zip(huMoments, huMoments[1:]):
        if x.diff(y) < 1.0e-9:
            print(path.basename(x.fname), path.basename(y.fname), x.diff(y))
            remove_list.append(y.fname)
    for fname in remove_list:
        os.remove(fname)

"""PaddleOCR rectangle offset interface, implemented by the actual GEOS polygon buffer.

The upstream detector only uses closed round polygon offsets; unsupported modes
raise rather than silently approximating unrelated clipping operations.
"""
JT_ROUND=1
ET_CLOSEDPOLYGON=0
class PyclipperOffset:
    def __init__(self,*args,**kwargs):self.paths=[]
    def AddPath(self,path,join,end):
        if join!=JT_ROUND or end!=ET_CLOSEDPOLYGON:raise ValueError('Only closed round offsets are used by the Android OCR adapter')
        self.paths.append(path)
    def Execute(self,distance):
        from shapely.geometry import Polygon
        result=[]
        for path in self.paths:
            geometry=Polygon(path).buffer(float(distance),resolution=16,join_style=1)
            polygons=list(geometry.geoms) if hasattr(geometry,'geoms') else [geometry]
            for polygon in polygons:
                if not polygon.is_empty:result.append([[int(round(x)),int(round(y))] for x,y in list(polygon.exterior.coords)[:-1]])
        return result

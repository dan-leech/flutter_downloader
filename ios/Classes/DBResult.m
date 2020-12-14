//
// Created by Daniil Kostin on 14.12.2020.
//

#import "DBResult.h"

@interface DBResult()

@end

@implementation DBResult
-(instancetype)init {
    self = [super init];
    if (self) {
        self.arrResults = [[NSMutableArray alloc] init];
        
        self.arrColumnNames = [[NSMutableArray alloc] init];;
    }
    return self;
}@end


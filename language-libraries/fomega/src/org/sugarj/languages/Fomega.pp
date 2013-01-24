[
   TyVar                     -- _1,
   TyNat                     -- KW["Nat"],
   TyBool                    -- KW["Bool"],
   TyString                  -- KW["String"],
   TyRecord                  -- KW["{"] _1 KW["}"],
   TyVariant                 -- KW["<"] _1 KW[">"],
   TyDecl                    -- _1 KW[":"] _2,
   TyDeclsNil                -- ,
   TyDeclsCons               -- _1 KW[","] _2,
   KiStar                    -- KW["*"],
   KiArrow                   -- _1 KW["=>"] _2,
   TyQVar                    -- _1,
   TyApp                     -- _1 _2,
   TyArrow                   -- _1 KW["->"] _2,
   TyForall                  -- KW["forall"] _1 KW["::"] _2 KW["."] _3,
   TyAbs                     -- KW["\\"] _1 KW["::"] _2 KW["."] _3,
   TyMu                      -- KW["mu"] _1 KW["."] _2,
   ModuleDec                 -- KW["module"] _1,
   Module                    -- _1 _2 _3,
   FomegaImports             -- _1,
   FomegaImports.1:iter-star -- _1,
   Import                    -- KW["import"] _1 _2,
   Import.1:opt              -- _1,
   FomegaBody                -- _1,
   FomegaBody.1:iter         -- _1,
   ValDef                    -- _1 KW["val"] _2 KW["="] _3,
   ValDef.1:opt              -- _1,
   TypeDef                   -- _1 KW["type"] _2 KW["="] _3,
   TypeDef.1:opt             -- _1,
   Public                    -- KW["public"],
   Qualified                 -- KW["qualified"],
   Var                       -- _1,
   QVar                      -- _1,
   Record                    -- KW["{"] _1 KW["}"],
   Variant                   -- KW["<"] _1 KW[">"] KW["as"] _2,
   True                      -- KW["true"],
   False                     -- KW["false"],
   Nat                       -- _1,
   String                    -- _1,
   Field                     -- _1 KW["="] _2,
   FieldsNil                 -- ,
   FieldsCons                -- _1 KW[","] _2,
   Branch                    -- KW["<"] _1 KW["="] _2 KW[">"] KW["=>"] _3,
   BranchesEnd               -- _1,
   BranchesCons              -- _1 KW[";"] _2,
   SelectRcd                 -- _1 KW["!"] _2,
   App                       -- _1 _2,
   TApp                      -- _1 KW["["] _2 KW["]"],
   Fold                      -- KW["fold"] KW["["] _1 KW["]"] _2,
   Unfold                    -- KW["unfold"] KW["["] _1 KW["]"] _2,
   Ascription                -- _1 KW["as"] _2,
   Abs                       -- KW["\\"] _1 KW[":"] _2 KW["."] _3,
   TAbs                      -- KW["\\"] _1 KW["::"] _2 KW["."] _3,
   Cond                      -- KW["if"] _1 KW["then"] _2 KW["else"] _3,
   Case                      -- KW["case"] _1 KW["of"] _2
]

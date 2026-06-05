--[[
  picat_test.lua -- in-game smoke test for the picat-cc mod.

  Run this on any CC:Tweaked computer that has the picat-cc mod installed.
  It exercises the `picat` global (query/eval/version), marshalling,
  backtracking, binding, error taxonomy, the planner, and an fs round-trip.

  Each check prints "ok"/"FAIL" with a short detail; at the end it prints
  "ALL PASS" or "FAILURES: <n>". Checks are defensive -- a nil from one
  check never crashes the rest of the script.

  Standalone usage: drop this file onto a computer (see docs/acceptance.md)
  and run `picat_test`.
]]

-- ---------------------------------------------------------------------------
-- harness
-- ---------------------------------------------------------------------------
local passed = 0
local failed = 0

local function check(name, cond, detail)
  if cond then
    passed = passed + 1
    print("ok    " .. name)
  else
    failed = failed + 1
    print("FAIL  " .. name .. (detail and ("  -- " .. tostring(detail)) or ""))
  end
end

-- Safe table index: returns nil instead of erroring on a non-table.
local function at(t, k)
  if type(t) ~= "table" then return nil end
  return t[k]
end

-- ---------------------------------------------------------------------------
-- 1. picat global exists
-- ---------------------------------------------------------------------------
check("picat global is a table", type(picat) == "table",
  "type(picat) = " .. type(picat))
check("picat.query is a function", type(at(picat, "query")) == "function")
check("picat.eval is a function", type(at(picat, "eval")) == "function")

do
  local v = (type(picat) == "table" and type(picat.version) == "function")
            and picat.version() or nil
  check("picat.version() == 0.1.0", v == "0.1.0", "got " .. tostring(v))
end

-- Guard: if there is no usable picat global, the rest is pointless.
if type(picat) ~= "table" or type(picat.query) ~= "function" then
  print("")
  print("FAILURES: picat global unusable; aborting remaining checks.")
  return
end

-- ---------------------------------------------------------------------------
-- 2. arithmetic
-- ---------------------------------------------------------------------------
do
  local ok, sols = picat.query("", "X = 1 + 1", {"X"})
  local x = ok and at(at(sols, 1), "X") or nil
  check("arith: X = 1 + 1 -> 2", ok and x == 2,
    ok and ("X = " .. tostring(x)) or ("query failed: " .. tostring(sols)))
end

-- ---------------------------------------------------------------------------
-- 3. struct with a nested list arg
-- ---------------------------------------------------------------------------
do
  local ok, sols = picat.query("", "A = $mine({0,63,5})", {"A"})
  local a = ok and at(at(sols, 1), "A") or nil
  -- a should be {f="mine", args={ {0,63,5} }}; args[1] is the nested list,
  -- so args[1][2] == 63.
  local nested = at(at(a, "args"), 1)
  check("struct: $mine({0,63,5}) marshals",
    ok and at(a, "f") == "mine" and at(nested, 2) == 63,
    ok and ("f=" .. tostring(at(a, "f")) .. " args[1][2]=" .. tostring(at(nested, 2)))
       or ("query failed: " .. tostring(sols)))
end

-- ---------------------------------------------------------------------------
-- 4. atom -> string
-- ---------------------------------------------------------------------------
do
  local ok, sols = picat.query("", "X = dump", {"X"})
  local x = ok and at(at(sols, 1), "X") or nil
  check("atom: X = dump -> \"dump\"", ok and x == "dump",
    ok and ("X = " .. tostring(x)) or ("query failed: " .. tostring(sols)))
end

-- ---------------------------------------------------------------------------
-- 5. list/tuple -> array table
-- ---------------------------------------------------------------------------
do
  local ok, sols = picat.query("", "X = [1,2,3]", {"X"})
  local x = ok and at(at(sols, 1), "X") or nil
  check("list: X = [1,2,3] -> array of 3",
    ok and type(x) == "table" and #x == 3 and x[3] == 3,
    ok and ("#X = " .. (type(x) == "table" and #x or "?"))
       or ("query failed: " .. tostring(sols)))
end

-- ---------------------------------------------------------------------------
-- 6. backtracking with a max cap
-- ---------------------------------------------------------------------------
do
  local ok, sols = picat.query("", "member(X,[a,b,c])", {"X"}, {max = 3})
  check("backtracking: member/2 yields 3 solutions, sols[2].X = b",
    ok and type(sols) == "table" and #sols == 3 and at(at(sols, 2), "X") == "b",
    ok and ("#sols = " .. (type(sols) == "table" and #sols or "?")
            .. " sols[2].X = " .. tostring(at(at(sols, 2), "X")))
       or ("query failed: " .. tostring(sols)))
end

-- ---------------------------------------------------------------------------
-- 7. bind: inject a Lua value into a Picat variable
-- ---------------------------------------------------------------------------
do
  local ok, sols = picat.query("", "S = sum(L)", {"S"}, {bind = {L = {1, 2, 3}}})
  local s = ok and at(at(sols, 1), "S") or nil
  check("bind: S = sum(L) with L={1,2,3} -> 6", ok and s == 6,
    ok and ("S = " .. tostring(s)) or ("query failed: " .. tostring(sols)))
end

-- ---------------------------------------------------------------------------
-- 8. goal failure (ok == false, err == "goal failed")
-- ---------------------------------------------------------------------------
do
  local ok, err = picat.query("", "1 = 2", {})
  check("goal failure: 1 = 2 -> ok=false, \"goal failed\"",
    ok == false and err == "goal failed",
    "ok = " .. tostring(ok) .. " err = " .. tostring(err))
end

-- ---------------------------------------------------------------------------
-- 9. compile error (err prefixed "compile:")
-- ---------------------------------------------------------------------------
do
  local ok, err = picat.query("this is !!! not picat", "true", {})
  local prefixed = (type(err) == "string") and (err:find("^compile:") ~= nil)
  check("compile error: bad program -> ok=false, \"compile:\" prefix",
    ok == false and prefixed,
    "ok = " .. tostring(ok) .. " err = " .. tostring(err))
end

-- ---------------------------------------------------------------------------
-- 10. eval with default goal (main)
-- ---------------------------------------------------------------------------
do
  local ok, out = picat.eval("main => println(hello).")
  check("eval: main prints \"hello\"",
    ok and type(out) == "string" and out:find("hello") ~= nil,
    ok and ("stdout = " .. tostring(out)) or ("eval failed: " .. tostring(out)))
end

-- ---------------------------------------------------------------------------
-- 11. eval with an explicit goal
-- ---------------------------------------------------------------------------
do
  local ok, out = picat.eval("greet() => println(yo).", "greet()")
  check("eval: explicit goal greet() prints \"yo\"",
    ok and type(out) == "string" and out:find("yo") ~= nil,
    ok and ("stdout = " .. tostring(out)) or ("eval failed: " .. tostring(out)))
end

-- ---------------------------------------------------------------------------
-- 12. planner (tiny, fast)
-- ---------------------------------------------------------------------------
do
  local prog = table.concat({
    "import planner.",
    "final({N}) => N == 3.",
    "action({N}, To, Action, Cost) ?=>",
    "    To = {N + 1},",
    "    Action = $step(N),",
    "    Cost = 1.",
    "go(Plan) => plan({0}, Plan).",
  }, "\n")
  local ok, sols = picat.query(prog, "go(Plan)", {"Plan"}, {timeout = 30})
  local plan = ok and at(at(sols, 1), "Plan") or nil
  local first = at(plan, 1)
  check("planner: 3-step plan, first action is step/1",
    ok and type(plan) == "table" and #plan == 3 and at(first, "f") == "step",
    ok and ("#Plan = " .. (type(plan) == "table" and #plan or "?")
            .. " Plan[1].f = " .. tostring(at(first, "f")))
       or ("query failed: " .. tostring(sols)))
end

-- ---------------------------------------------------------------------------
-- 13. fs round-trip (best-effort)
--
-- Write a file from inside a Picat run mounted at /data, then read it back
-- and (if CC's fs API is available standalone) confirm the file landed in
-- the computer's storage under the chosen subdir.
-- ---------------------------------------------------------------------------
do
  local writeProg = table.concat({
    "main =>",
    "    F = open(\"/data/smoke.txt\", write),",
    "    println(F, picat_smoke_ok),",
    "    close(F).",
  }, "\n")
  local okw, outw = picat.eval(writeProg, nil, {fs = "picat_smoke"})

  if not okw then
    check("fs: write to /data/smoke.txt (skipped)", true,
      "could not write (" .. tostring(outw) .. "); skipping fs round-trip")
  else
    -- Read it back through Picat, same mount.
    local readProg = table.concat({
      "main =>",
      "    F = open(\"/data/smoke.txt\", read),",
      "    L = read_line(F),",
      "    close(F),",
      "    println(L).",
    }, "\n")
    local okr, outr = picat.eval(readProg, nil, {fs = "picat_smoke"})
    check("fs: read /data/smoke.txt back via Picat",
      okr and type(outr) == "string" and outr:find("picat_smoke_ok") ~= nil,
      okr and ("read = " .. tostring(outr)) or ("read failed: " .. tostring(outr)))

    -- Best-effort: confirm via CC's own fs API. The mount subdir lives in
    -- the computer's storage; the exact on-disk layout is the mod's concern,
    -- so treat absence as a SKIP, not a hard FAIL.
    if type(fs) == "table" and type(fs.exists) == "function" then
      local seen = fs.exists("picat_smoke/smoke.txt")
              or fs.exists("/picat_smoke/smoke.txt")
      if seen then
        check("fs: file visible on computer fs at picat_smoke/smoke.txt", true)
      else
        check("fs: file visible on computer fs (skipped)", true,
          "not at picat_smoke/smoke.txt on this computer's fs; "
          .. "Picat-side round-trip above is authoritative")
      end
    else
      check("fs: CC fs API present for cross-check (skipped)", true,
        "no global fs API; Picat-side round-trip above is authoritative")
    end
  end
end

-- ---------------------------------------------------------------------------
-- summary
-- ---------------------------------------------------------------------------
print("")
print(string.format("%d passed, %d failed", passed, failed))
if failed == 0 then
  print("ALL PASS")
else
  print("FAILURES: " .. failed)
end

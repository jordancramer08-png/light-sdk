@echo off
rem Puts a chosen backup back on the Phone (backs up first, asks for YES).
rem Add /preview to only check and show what would happen.
call "%~dp0kit\_run.cmd" restore %*
